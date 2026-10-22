package dev.fondaria.rules;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One effective-dated version of a plan's rulebook — {@code reference.plan_version}.
 *
 * <p>"Thin" by design (BACKLOG.md step 5, "the configurability proof"): a real plan naming, as
 * {@code docs/DOMAIN.md} §6 requires, carries contribution percentages, caps, withdrawal rules,
 * eligible funds and a fee schedule as well. This version carries only the two things this step
 * needs — the employer matching ratio, which {@link MatchingRatioRule} enforces against
 * contribution rows, and the vesting schedule, which {@link VestingScheduleCalculator} reads but
 * nothing here enforces yet. Both are stored as versioned data rather than constants because
 * docs/ENGINEERING-RULES.md §4 forbids treating plan rules as code constants, and ADR-0004 states
 * the vesting schedule specifically is "per-organization, effective-dated configuration."
 *
 * <p>{@code planId} is a free-text identifier, matching the convention already established by
 * {@code BatchDeclaration.planId} and {@code master.participant.plan_id} — not a new derived-id
 * type.
 *
 * @param effectiveFrom the date this version takes effect. Resolution ({@link
 *     PlanVersionRepository#resolveAsOf}) picks the version with the latest {@code effectiveFrom}
 *     not after the date asked for.
 * @param version disambiguates two versions published with the same {@code effectiveFrom} — a
 *     correction of a mistaken publication. The higher value wins resolution; the lower one remains
 *     readable (Task 2b.2 acceptance criterion 3).
 * @param employerMatchRatioNumerator together with {@code employerMatchRatioDenominator}, the ratio
 *     an employer's contribution must bear to the matching employee contribution — e.g. 1/1 for a
 *     dollar-for-dollar match. Both strictly positive.
 * @param vestingFullYears years of continuous service (from a participant's employment start date)
 *     at which a graded vesting schedule reaches 100%. Zero means immediate vesting.
 * @param dealingLagBusinessDays business days between a batch's value date and the valuation date
 *     it deals at (Task 21.1). Zero means same-day dealing. Versioned rather than configured for
 *     the reason docs/ENGINEERING-RULES.md §4 gives for every plan rule: an employer that
 *     renegotiates its lag needs the old value to stay readable, because a batch allotted last
 *     March was priced under the lag in force last March and that has to be reproducible years
 *     later from stored data. Read by {@link DealingDateRule}, which is bounded by the same {@link
 *     DealingDateRule#MAXIMUM_DEALING_LAG_BUSINESS_DAYS} this constructor enforces.
 * @param fundId the fund this plan's contributions allot into ({@code ADR-0012}: one fund per plan
 *     version for v1 — no participant-chosen allocation, switching or rebalancing). Versioned
 *     alongside everything else here for the reason {@code dealingLagBusinessDays} is: a batch
 *     allotted under an earlier plan version must remain explicable from the fund in force when it
 *     was allotted, not from whichever fund the plan names today. Read by Task 21.2's allotment run
 *     to resolve which fund's NAV a staged row strikes against.
 */
public record PlanVersion(
    OrganizationId organizationId,
    String planId,
    LocalDate effectiveFrom,
    int version,
    int employerMatchRatioNumerator,
    int employerMatchRatioDenominator,
    int vestingFullYears,
    int dealingLagBusinessDays,
    FundId fundId,
    Instant publishedAt) {

  public PlanVersion {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    if (planId == null || planId.isBlank()) {
      throw new IllegalArgumentException("planId must not be blank");
    }
    planId = planId.trim();
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    if (employerMatchRatioNumerator <= 0 || employerMatchRatioDenominator <= 0) {
      throw new IllegalArgumentException(
          "employerMatchRatioNumerator and employerMatchRatioDenominator must both be positive");
    }
    if (vestingFullYears < 0) {
      throw new IllegalArgumentException("vestingFullYears must not be negative");
    }
    if (dealingLagBusinessDays < 0
        || dealingLagBusinessDays > DealingDateRule.MAXIMUM_DEALING_LAG_BUSINESS_DAYS) {
      throw new IllegalArgumentException(
          "dealingLagBusinessDays must be between 0 and "
              + DealingDateRule.MAXIMUM_DEALING_LAG_BUSINESS_DAYS);
    }
  }
}
