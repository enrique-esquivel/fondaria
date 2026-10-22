package dev.fondaria.eodjob.custodian;

import dev.fondaria.eodjob.reconciliation.RunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A custodian-reconciliation pass as recorded — the row Task 7.5's sign-off reads.
 *
 * <p>Reuses {@link RunStatus} from the {@code reconciliation} package: {@code RUNNING}/{@code
 * PASSED}/{@code FAILED} means the same thing here as it does for Task 7.2's internal pass, and
 * both packages live in the one {@code eod-job} service (`ADR-0018`) — there is nothing to
 * duplicate by sharing it.
 */
public record CustodianReconciliationRun(
    UUID runId,
    LocalDate businessDate,
    RunStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    Optional<Long> durationMillis,
    int providerAccountCount,
    int statementsReceivedCount,
    int missingStatementCount,
    int realBreakCount,
    int timingDifferenceCount) {

  public CustodianReconciliationRun {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(durationMillis, "durationMillis must not be null");
  }

  /** Whether this run permits sign-off (Task 7.5) to proceed for this date. */
  public boolean opensTheGate() {
    return status == RunStatus.PASSED;
  }
}
