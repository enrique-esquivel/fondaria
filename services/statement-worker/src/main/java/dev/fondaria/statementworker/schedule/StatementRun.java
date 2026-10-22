package dev.fondaria.statementworker.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** One row of {@code workflow.statement_run} ({@code V30}) — see that migration's own comment. */
public record StatementRun(
    String period,
    LocalDate closeBusinessDate,
    StatementRunStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    Optional<Long> durationMillis,
    long expectedCount,
    Optional<Long> completedCount,
    Optional<String> failureReason) {

  public StatementRun {
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(closeBusinessDate, "closeBusinessDate must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(durationMillis, "durationMillis must not be null");
    Objects.requireNonNull(completedCount, "completedCount must not be null");
    Objects.requireNonNull(failureReason, "failureReason must not be null");
  }
}
