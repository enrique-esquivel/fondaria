package dev.fondaria.contribution.plan.web;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The body a caller submits to publish a plan version (Task D.0, {@code phase-D-demo.md}). Mirrors
 * {@code dev.fondaria.rules.PlanVersion}; {@code organizationId} comes from the path.
 */
public record PublishPlanVersionRequest(
    String planId,
    String effectiveFrom,
    Integer version,
    Integer employerMatchRatioNumerator,
    Integer employerMatchRatioDenominator,
    Integer vestingFullYears,
    Integer dealingLagBusinessDays,
    String fundId) {

  String parsedPlanId() {
    if (planId == null || planId.isBlank()) {
      throw new InvalidPlanVersionRequestException("planId must not be blank");
    }
    return planId.trim();
  }

  LocalDate parsedEffectiveFrom() {
    if (effectiveFrom == null || effectiveFrom.isBlank()) {
      throw new InvalidPlanVersionRequestException("effectiveFrom must be supplied, as YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(effectiveFrom.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidPlanVersionRequestException(
          "effectiveFrom must be a date in YYYY-MM-DD form");
    }
  }

  int parsedVersion() {
    if (version == null) {
      throw new InvalidPlanVersionRequestException("version must be supplied");
    }
    return version;
  }

  int parsedEmployerMatchRatioNumerator() {
    if (employerMatchRatioNumerator == null) {
      throw new InvalidPlanVersionRequestException("employerMatchRatioNumerator must be supplied");
    }
    return employerMatchRatioNumerator;
  }

  int parsedEmployerMatchRatioDenominator() {
    if (employerMatchRatioDenominator == null) {
      throw new InvalidPlanVersionRequestException(
          "employerMatchRatioDenominator must be supplied");
    }
    return employerMatchRatioDenominator;
  }

  int parsedVestingFullYears() {
    if (vestingFullYears == null) {
      throw new InvalidPlanVersionRequestException("vestingFullYears must be supplied");
    }
    return vestingFullYears;
  }

  int parsedDealingLagBusinessDays() {
    if (dealingLagBusinessDays == null) {
      throw new InvalidPlanVersionRequestException("dealingLagBusinessDays must be supplied");
    }
    return dealingLagBusinessDays;
  }

  FundId parsedFundId() {
    if (fundId == null || fundId.isBlank()) {
      throw new InvalidPlanVersionRequestException("fundId must be supplied");
    }
    try {
      return FundId.parse(fundId.trim());
    } catch (IllegalArgumentException e) {
      throw new InvalidPlanVersionRequestException("fundId is not a valid UUID");
    }
  }
}
