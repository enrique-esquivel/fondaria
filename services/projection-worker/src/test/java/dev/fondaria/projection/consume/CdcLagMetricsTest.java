package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.projection.config.CheckpointProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The pod-level head and lag gauges (ADR-0032 §4): {@code MIN} over this pod's consumers active
 * within the window, falling back to {@code MAX} when all are idle — the same rule as {@link
 * ProjectionCheckpoint#HEAD}.
 */
class CdcLagMetricsTest {

  private static final Instant T0 = Instant.parse("2026-09-28T12:00:00Z");
  private static final long T0_NANOS = T0.getEpochSecond() * 1_000_000_000L;

  private final MutableClock clock = new MutableClock(T0);
  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final CdcLagMetrics metrics =
      new CdcLagMetrics(
          registry,
          clock,
          new CheckpointProperties("pod", Duration.ofMinutes(5), Duration.ofHours(1)));

  @Test
  void noCommitsYetReportsNoLag() {
    assertThat(metrics.podHeadNanos()).isZero();
    assertThat(lagSeconds()).isZero();
  }

  @Test
  void headIsTheSlowestActiveConsumerNotTheFastest() {
    metrics.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(10)));
    metrics.lastTimestampAdvancedTo("pod/b", nanos(T0.minusSeconds(600)));

    // The old single-row GREATEST would report 10 s; the hot shard is 600 s behind.
    assertThat(lagSeconds()).isEqualTo(600.0);
  }

  @Test
  void anIdleConsumerDropsOutOfTheHead() {
    metrics.lastTimestampAdvancedTo("pod/quiet-shard", nanos(T0.minusSeconds(3_600)));
    clock.advance(Duration.ofMinutes(6));
    metrics.lastTimestampAdvancedTo("pod/busy", nanos(clock.instant().minusSeconds(2)));

    assertThat(lagSeconds()).isEqualTo(2.0);
  }

  @Test
  void allIdleFallsBackToTheHighestMark() {
    metrics.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(30)));
    metrics.lastTimestampAdvancedTo("pod/b", nanos(T0.minusSeconds(90)));
    clock.advance(Duration.ofMinutes(10));

    assertThat(metrics.podHeadNanos()).isEqualTo(T0_NANOS - 30_000_000_000L);
    // Nothing is arriving, so the lag grows from the newest committed event.
    assertThat(lagSeconds()).isEqualTo(630.0);
  }

  @Test
  void aConsumerMarkNeverMovesBackward() {
    metrics.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(5)));
    metrics.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(50)));

    assertThat(lagSeconds()).isEqualTo(5.0);
  }

  @Test
  void forgottenConsumersNoLongerCount() {
    metrics.lastTimestampAdvancedTo("pod/dead", nanos(T0.minusSeconds(100)));
    clock.advance(Duration.ofHours(2));
    metrics.lastTimestampAdvancedTo("pod/live", nanos(clock.instant().minusSeconds(1)));

    metrics.forgetIdleSince(clock.instant().minus(Duration.ofHours(1)));
    clock.advance(Duration.ofMinutes(10));

    // Only pod/live remains; all-idle fallback reads its mark, not the dead consumer's.
    assertThat(lagSeconds()).isEqualTo(601.0);
  }

  @Test
  void aStallLongerThanTheRetentionKeepsReportingAGrowingLag() {
    // Every consumer stops (a sink failure retrying forever, or a halt). Pruning must not reset
    // the gauge to "nothing consumed yet" while the queues still hold work.
    metrics.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(5)));
    metrics.lastTimestampAdvancedTo("pod/b", nanos(T0.minusSeconds(20)));
    clock.advance(Duration.ofHours(2));
    metrics.forgetIdleSince(clock.instant().minus(Duration.ofHours(1)));

    assertThat(lagSeconds()).isEqualTo(7_205.0);
    clock.advance(Duration.ofMinutes(10));
    assertThat(lagSeconds()).isEqualTo(7_805.0);
  }

  private double lagSeconds() {
    return registry.get("ledger_cdc_lag_seconds").gauge().value();
  }

  private static String nanos(Instant instant) {
    return Long.toString(instant.getEpochSecond() * 1_000_000_000L);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
