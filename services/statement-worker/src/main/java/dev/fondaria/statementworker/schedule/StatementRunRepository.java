package dev.fondaria.statementworker.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@code workflow.statement_run} ({@code V30}) — see that migration's own comment. */
public interface StatementRunRepository {

  /**
   * Opens a run {@code RUNNING}, if one does not already exist for this period.
   *
   * <p><b>Never a gate over whether an in-flight fan-out proceeds</b> — see {@link
   * StatementScheduler}, which skips a redelivery only once {@link #find} shows the period already
   * {@code COMPLETED}, never merely because a row exists. This is an {@code INSERT ... ON CONFLICT
   * (period) DO NOTHING}: the first attempt at a period records {@code started_at} and {@code
   * expected_count}; a later attempt while the period is still open (a redelivery of {@code
   * close.signed-off.v1} after a scheduler crash) is a no-op here and proceeds to publish jobs
   * regardless of whether the row already existed.
   */
  void openIfAbsent(
      String period, LocalDate closeBusinessDate, Instant startedAt, long expectedCount);

  /** Every period still {@code RUNNING} — what the settlement sweep checks against Redis. */
  List<StatementRun> findRunning();

  /** Settles a run {@code COMPLETED}, copying back the counter's final value. */
  void complete(String period, Instant completedAt, long durationMillis, long completedCount);

  Optional<StatementRun> find(String period);
}
