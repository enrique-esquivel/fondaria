package dev.fondaria.eodjob.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A reconciliation pass as recorded — the row Task 7.5's sign-off reads and the row an operator
 * finds when a close did not happen.
 *
 * @param businessDate the close this run belongs to, from {@code BusinessCalendar} and never {@code
 *     LocalDate.now()}
 * @param durationMillis how long the comparison took; the phase requires it be recorded
 */
public record ReconciliationRun(
    UUID runId,
    LocalDate businessDate,
    RunStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    Optional<Long> durationMillis,
    int fundCount,
    long holdingAccountCount,
    int cashAccountCount,
    int breakCount) {

  public ReconciliationRun {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(durationMillis, "durationMillis must not be null");
  }

  /** Whether this run permits the rest of the daily close to proceed. */
  public boolean opensTheGate() {
    return status == RunStatus.PASSED;
  }
}
