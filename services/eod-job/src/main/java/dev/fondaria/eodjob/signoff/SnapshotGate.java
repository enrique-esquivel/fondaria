package dev.fondaria.eodjob.signoff;

import dev.fondaria.eodjob.snapshot.SnapshotRepository;
import dev.fondaria.eodjob.snapshot.SnapshotRun;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The third gate sign-off passes through: <b>no dense snapshot of record, no sign-off</b> (Task
 * 7.5a).
 *
 * <p>The counterpart of {@code ReconciliationGate} (Task 7.2) and {@code
 * CustodianReconciliationGate} (Task 7.3), which existed already; the snapshot (Task 7.4) had no
 * gate class because nothing downstream of it needed one until now.
 *
 * <p><b>What "of record" means here.</b> This reads {@link SnapshotRepository#latestCompletedFor} —
 * the most recently settled {@code COMPLETED} run for the date. That is Task 7.4's own definition
 * of the snapshot of record, and a {@code COMPLETED} run is one whose per-fund unit totals tied to
 * {@code units_in_issue} when it ran (a run that did not tie is never stored, {@code ADR-0020}). A
 * <i>later</i> verification pass that found the book no longer ties records a {@code FAILED} run
 * but does not un-take the {@code COMPLETED} one; that divergence is surfaced by {@code
 * SnapshotRunner}'s metrics and log, and re-opening the close for that date is Task 7.6's runbook.
 * This gate does not re-read the snapshot's tie — it trusts that a {@code COMPLETED} run tied, the
 * same way {@link SignoffGate} trusts that a {@code close_signoff} row means signed off.
 *
 * <p>Unlike the two reconciliation gates, there is no latest-to-settle race to reason about here:
 * {@code snapshot_run_one_of_record_per_date} ({@code V28}) is a partial unique index on {@code
 * (business_date) WHERE status = 'COMPLETED'}, so {@code latestCompletedFor} can only ever match
 * one row. The reconciliation tables have no such constraint, which is why {@code
 * ReconciliationGate} and {@code CustodianReconciliationGate} return the run they validated rather
 * than letting the caller re-read it.
 */
@Component
public class SnapshotGate {

  private final SnapshotRepository snapshots;

  public SnapshotGate(SnapshotRepository snapshots) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
  }

  /**
   * The snapshot of record for this date.
   *
   * @throws SnapshotNotTakenException if none exists
   */
  public SnapshotRun requireTaken(LocalDate businessDate) {
    return snapshots
        .latestCompletedFor(businessDate)
        .orElseThrow(() -> new SnapshotNotTakenException(businessDate));
  }
}
