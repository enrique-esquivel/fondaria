package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code workflow.allotment_residual} (V17) — the visible, bounded record of what
 * flooring every participant-source contribution to the unit scale discarded this run ({@code
 * docs/rounding-policy.md}, Task 21.3).
 *
 * <p>Never a ledger posting: {@link #residual} is a fact about the run, not a transfer. {@code
 * FUND_CASH} already holds this value — it arrived with the batch's full cash legs — and {@code
 * UNITS_IN_ISSUE} is never debited by it.
 *
 * @param rowCount the number of participant-source contribution legs priced this run, each one
 *     floor division — the quantity {@link AllotmentService} bounds {@link #residual} against
 * @param navVersionId the {@code reference.fund_nav} row this run struck units at
 * @param residual MXN cents, always non-negative: cash allotted minus the value of units issued
 */
public record AllotmentResidual(
    OrganizationId organizationId,
    UUID batchId,
    FundId fundId,
    long navVersionId,
    int rowCount,
    Money residual,
    Instant recordedAt) {

  public AllotmentResidual {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(residual, "residual must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    if (rowCount <= 0) {
      throw new IllegalArgumentException("rowCount must be positive, got: " + rowCount);
    }
    if (residual.isNegative()) {
      throw new IllegalArgumentException(
          "residual must never be negative (docs/ENGINEERING-RULES.md §4: rounding is always in the fund's"
              + " favour), got: "
              + residual.cents());
    }
  }
}
