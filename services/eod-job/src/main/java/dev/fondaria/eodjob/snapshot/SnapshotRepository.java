package dev.fondaria.eodjob.snapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code workflow.snapshot_run} and {@code workflow.snapshot_fund_total} ({@code V28}). */
public interface SnapshotRepository {

  /**
   * Opens a run {@code RUNNING}, before any position is read.
   *
   * <p>Same posture as {@code ReconciliationRepository#open}: a process that dies mid-pass leaves a
   * row that is visibly not a snapshot, rather than no row at all. Nothing downstream can tell
   * "never ran" from "crashed" — both are simply not a snapshot — but an operator can.
   */
  void open(UUID runId, LocalDate businessDate, Instant startedAt);

  /**
   * Records, on a still-{@code RUNNING} run, the object key and content hash it is about to store.
   *
   * <p>Written before the bytes leave, so a pass that dies between the two leaves behind what it
   * intended. An object under a date with no {@code COMPLETED} run is not a snapshot of record —
   * nothing references it, and the next pass replacing it is how a failed pass recovers — but an
   * operator looking at a bucket needs to be able to tell which run put it there.
   */
  void recordIntent(UUID runId, String positionsObjectKey, String positionsSha256);

  /** Settles a run and writes its per-fund totals, in one transaction. */
  void complete(SnapshotRun run, List<SnapshotFundTotal> fundTotals);

  /**
   * Settles a run {@code FAILED} with the operator-facing reason.
   *
   * <p>No fund totals are written. A pass that failed produced no file, and per-fund totals are the
   * evidence for a tie a file made — recording them for a run with nothing behind them would put
   * numbers in the audit trail that no artefact backs.
   */
  void fail(UUID runId, Instant failedAt, long durationMillis, String reason);

  /**
   * The snapshot of record for a date, if one was taken: the most recently settled {@code
   * COMPLETED} run. What a re-run compares itself against.
   */
  Optional<SnapshotRun> latestCompletedFor(LocalDate businessDate);

  /**
   * The most recently settled {@code COMPLETED} run for any <b>strictly earlier</b> business date —
   * the link this date's manifest chains to.
   *
   * <p>Strictly earlier, and by business date rather than by clock: a snapshot re-taken for last
   * Tuesday today is still last Tuesday's, and a chain that followed completion order would reorder
   * itself the moment a past date was revisited. Empty is the genesis of the chain.
   */
  Optional<SnapshotRun> latestCompletedBefore(LocalDate businessDate);

  /** A run's per-fund totals, in unit-ledger order. For tests and for an operator's tie-out. */
  List<SnapshotFundTotal> fundTotalsOf(UUID runId);

  /**
   * Every completed snapshot run, ordered by business date ascending. Used to walk the snapshot
   * manifest chain from genesis forward.
   */
  List<SnapshotRun> completedRunsAscending();
}
