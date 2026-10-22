package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.allotmentjob.fund.FundDirectory;
import dev.fondaria.allotmentjob.nav.AllotmentNavSource;
import dev.fondaria.allotmentjob.plan.PlanVersionDirectory;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.PlanVersion;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link PlanVersionDirectory}, {@link FundDirectory} and {@link AllotmentNavSource} —
 * one candidate value per key, since {@link AllotmentServiceTest} only ever needs "the version in
 * force as of this date", never a real effective-dating resolution (that is {@code
 * DealingDateRuleTest} and the JDBC integration tests' job).
 */
final class InMemoryDirectories implements PlanVersionDirectory, FundDirectory, AllotmentNavSource {

  private final Map<String, PlanVersion> planVersions = new HashMap<>();
  private final Map<FundId, Fund> funds = new HashMap<>();
  private final Map<String, FundNav> navs = new HashMap<>();

  void givenPlanVersion(PlanVersion planVersion) {
    planVersions.put(
        planVersion.organizationId().value() + ":" + planVersion.planId(), planVersion);
  }

  void givenFund(Fund fund) {
    funds.put(fund.fundId(), fund);
  }

  void givenNav(FundNav fundNav) {
    navs.put(fundNav.fundId().value() + ":" + fundNav.valuationDate(), fundNav);
  }

  @Override
  public Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
    return Optional.ofNullable(planVersions.get(organizationId.value() + ":" + planId));
  }

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return Optional.ofNullable(funds.get(fundId));
  }

  @Override
  public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
    return Optional.ofNullable(navs.get(fundId.value() + ":" + valuationDate));
  }
}
