package dev.fondaria.eodjob.snapshot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the platform pages on when the daily snapshot does not land.
 *
 * <p>Same division of labour as {@code ReconciliationMetrics}: the exception stops the close, these
 * meters are how a human finds out. Four, and no participant, organization or fund identifier on
 * any of them — a label is as much a log line as a message is (docs/ENGINEERING-RULES.md §7), and
 * an alert only has to say <i>that</i> the snapshot is missing. Which fund did not tie is in {@code
 * workflow.snapshot_run.failure_reason}, behind authentication.
 *
 * <ul>
 *   <li>{@code eod_snapshot_missing} — {@code 1} while the latest pass did not produce a snapshot.
 *       <b>The gauge to alert on</b>, because it stays raised until one succeeds rather than firing
 *       once and clearing itself.
 *   <li>{@code eod_snapshot_positions} — rows in the most recent snapshot. A book that grows
 *       steadily and then does not is the shape of a scan that stopped early.
 *   <li>{@code eod_snapshot_untied_total} — funds that failed the units-in-issue tie, ever. The one
 *       failure here that means the file is wrong about participants' money rather than merely
 *       disagreeing with an earlier pass.
 *   <li>{@code eod_snapshot_duration} — how long a pass takes. The phase requires the duration be
 *       recorded; it is also the first thing to look at when a close starts missing its window.
 * </ul>
 *
 * <p>The alert rule and its runbook are Task 7.6's and Phase 11's to write — this class is the
 * signal they read.
 */
@Component
public class SnapshotMetrics {

  private final AtomicLong missing = new AtomicLong(1L);
  private final AtomicLong positions = new AtomicLong(0L);
  private final Counter untiedFunds;
  private final Timer duration;

  public SnapshotMetrics(MeterRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    // Starts at 1: before the first pass of a process, no snapshot has been proved to exist, and
    // an unproved close is a blocked close.
    registry.gauge("eod_snapshot_missing", missing, AtomicLong::doubleValue);
    registry.gauge("eod_snapshot_positions", positions, AtomicLong::doubleValue);
    this.untiedFunds =
        Counter.builder("eod_snapshot_untied_total")
            .description("Funds whose snapshot unit total did not equal their units in issue")
            .register(registry);
    this.duration =
        Timer.builder("eod_snapshot_duration")
            .description("How long one dense-snapshot pass took")
            .register(registry);
  }

  /** Records a pass that produced a snapshot — whether it stored one or reproduced an existing. */
  public void record(SnapshotResult result) {
    missing.set(0L);
    positions.set(result.positionCount());
    result.run().durationMillis().ifPresent(millis -> duration.record(Duration.ofMillis(millis)));
  }

  /**
   * Records a sweep that found the date already snapshotted and did nothing.
   *
   * <p>It clears {@code eod_snapshot_missing} without touching the duration or the position count:
   * the snapshot exists, which is the only thing the gauge claims, and reporting a duration for a
   * pass that did no work would make the close's timing history read as though it had got faster.
   */
  public void recordAlreadyTaken() {
    missing.set(0L);
  }

  /** Records a pass that produced no snapshot. Not a snapshot, so the gauge stays raised. */
  public void recordFailure() {
    missing.set(1L);
  }

  /** Records a tie failure, in addition to {@link #recordFailure()}. */
  public void recordUntied(int fundCount) {
    untiedFunds.increment(fundCount);
  }
}
