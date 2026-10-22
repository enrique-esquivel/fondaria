package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meters an operator watches for the last step of the close. {@code eod_signoff_blocked} starts
 * raised — an unproved close is a blocked close — and only a successful sweep clears it; {@code
 * eod_signoff_announce_pending} is set from a table read every sweep, not from one pass.
 */
class SignoffMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SignoffMetrics metrics = new SignoffMetrics(registry);

  private static CloseSignoff row() {
    return new CloseSignoff(
        LocalDate.of(2026, 8, 31),
        Instant.parse("2026-09-01T02:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        100L,
        "k/positions.ndjson.gz",
        "k/manifest.json",
        Optional.empty());
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  @Test
  @DisplayName("the blocked gauge starts at 1")
  void blockedStartsRaised() {
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(1.0);
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("a fresh sign-off clears blocked and counts one")
  void aFreshSignOff() {
    metrics.record(new SignoffResult(row(), true, true));

    assertThat(gauge("eod_signoff_blocked")).isEqualTo(0.0);
    assertThat(registry.get("eod_signoff_total").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("record does not double-count an already-signed-off date")
  void alreadySignedOffDoesNotCount() {
    metrics.record(new SignoffResult(row(), true, true));
    metrics.record(new SignoffResult(row(), false, true));

    assertThat(registry.get("eod_signoff_total").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("announce_pending tracks the count it is given, up and back down")
  void announcePendingFollowsTheCount() {
    metrics.setAnnouncePending(2);
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(2.0);

    metrics.setAnnouncePending(0);
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("a blocked sweep raises the gauge again but does not touch announce_pending")
  void aBlockedSweepRaisesBlockedOnly() {
    metrics.record(new SignoffResult(row(), true, true));
    metrics.setAnnouncePending(3);
    metrics.recordBlocked();

    assertThat(gauge("eod_signoff_blocked")).isEqualTo(1.0);
    assertThat(gauge("eod_signoff_announce_pending"))
        .as("pending rows are still pending whether or not today's gate is open")
        .isEqualTo(3.0);
  }
}
