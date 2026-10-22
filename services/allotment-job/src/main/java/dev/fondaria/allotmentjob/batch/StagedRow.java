package dev.fondaria.allotmentjob.batch;

import dev.fondaria.common.OrganizationId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code workflow.contribution_batch_row} (Task 3.6) — one participant's contribution
 * from a staged batch, as much of it as the allotment run needs.
 *
 * <p>Deliberately not the full set of columns that table carries: no {@code planId}, {@code
 * payrollPeriod}, {@code payrollRef} or {@code valueDate}, because allotment prices the whole batch
 * at one NAV resolved from the parent {@link StagedBatch}, never per row. Narrower on purpose, the
 * same way {@link dev.fondaria.allotmentjob.fund.FundDirectory} is narrower than {@code
 * FundRepository}.
 *
 * @param amounts every {@link ContributionSource} present with an explicit value, in MXN cents
 */
public record StagedRow(
    OrganizationId organizationId,
    UUID batchId,
    String participantInternalRef,
    Map<ContributionSource, Long> amounts) {

  public StagedRow {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    amounts = Map.copyOf(amounts);
  }

  public long amountOf(ContributionSource source) {
    return amounts.getOrDefault(source, 0L);
  }

  /** Every field but the amounts is person-identifying: a participant reference. */
  @Override
  public String toString() {
    return "StagedRow[batchId=" + batchId + ", REDACTED]";
  }
}
