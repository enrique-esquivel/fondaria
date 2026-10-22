package dev.fondaria.statementworker.snapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The two reads this service makes against {@code workflow.snapshot_run} (Task 7.4's table): which
 * completed snapshot is the prior month's closing book, and when a given run (the current month's,
 * named on {@code close.signed-off.v1}) began.
 */
public interface OpeningSnapshotRepository {

  /**
   * The most recently completed snapshot strictly before {@code firstDayOfMonth} — the prior
   * month's last business day, whatever it was. Empty if no snapshot has ever completed before that
   * date (the genesis case: the platform's very first statemented month).
   */
  Optional<OpeningSnapshot> latestCompletedBefore(LocalDate firstDayOfMonth);

  /**
   * When the run named by {@code runId} began ({@code workflow.snapshot_run.started_at}) — the
   * read-consistency cutoff {@code StatementScheduler} bounds {@code workflow.allotment} by. An
   * allotment written after the closing snapshot's pass began is not reflected in that snapshot's
   * units, so it must not be counted as this period's contribution either; the same reasoning
   * {@code eod-job}'s {@code JdbcCostBasisSource} bounds its own {@code allotted_at} join by,
   * applied here to keep the fan-out's two reads — the closing snapshot and the month's
   * contributions — consistent with each other.
   */
  Instant startedAtOf(UUID runId);
}
