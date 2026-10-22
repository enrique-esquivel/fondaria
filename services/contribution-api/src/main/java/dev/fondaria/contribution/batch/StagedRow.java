package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code workflow.contribution_batch_row}: what one participant contributed in a batch
 * that passed every check of {@code contribution-file-v2.md} §4, and the value date it is staged
 * for.
 *
 * <p><b>Captured once, immutable from the moment it exists.</b> {@link ContributionStagingService}
 * writes this at the same point {@code ParticipantEnrolmentService} enrols — structural-validation
 * time, before the reconciliation gate ever runs — so a row's data never depends on when, or how
 * many times, its batch is later re-swept. See {@link ContributionStagingService} for why this is
 * safe to write ahead of the gate rather than waiting for it.
 *
 * @param valueDate the batch's declared value date ({@code contribution-file-v1.md}: "the date this
 *     batch is staged for"), not the date this row was recorded
 * @param amounts every {@link ContributionSource} present with an explicit value, matching {@link
 *     ContributionFile.ParticipantRow#amounts()} exactly — staging restates nothing about the
 *     money, it only adds the batch's own plan, period and value date to a row the file already
 *     declared
 */
public record StagedRow(
    OrganizationId organizationId,
    UUID batchId,
    String participantInternalRef,
    String participantPayrollRef,
    String planId,
    String payrollPeriod,
    LocalDate valueDate,
    Map<ContributionSource, Long> amounts,
    Instant recordedAt) {

  public StagedRow {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    Objects.requireNonNull(participantPayrollRef, "participantPayrollRef must not be null");
    Objects.requireNonNull(planId, "planId must not be null");
    Objects.requireNonNull(payrollPeriod, "payrollPeriod must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    amounts = Map.copyOf(amounts);
  }

  public long amountOf(ContributionSource source) {
    return amounts.getOrDefault(source, 0L);
  }

  /** Every field but the amounts is person-identifying: two references and two dates. */
  @Override
  public String toString() {
    return "StagedRow[batchId=" + batchId + ", REDACTED]";
  }
}
