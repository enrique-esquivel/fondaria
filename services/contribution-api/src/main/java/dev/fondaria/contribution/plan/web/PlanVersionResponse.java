package dev.fondaria.contribution.plan.web;

import dev.fondaria.rules.PlanVersion;
import java.time.Instant;

/** A plan version as returned by the API. */
public record PlanVersionResponse(
    String organizationId,
    String planId,
    String effectiveFrom,
    int version,
    int employerMatchRatioNumerator,
    int employerMatchRatioDenominator,
    int vestingFullYears,
    int dealingLagBusinessDays,
    String fundId,
    Instant publishedAt) {

  public static PlanVersionResponse of(PlanVersion planVersion) {
    return new PlanVersionResponse(
        planVersion.organizationId().toString(),
        planVersion.planId(),
        planVersion.effectiveFrom().toString(),
        planVersion.version(),
        planVersion.employerMatchRatioNumerator(),
        planVersion.employerMatchRatioDenominator(),
        planVersion.vestingFullYears(),
        planVersion.dealingLagBusinessDays(),
        planVersion.fundId().toString(),
        planVersion.publishedAt());
  }
}
