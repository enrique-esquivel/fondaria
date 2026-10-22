package dev.fondaria.contribution.plan.web;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.fund.FundNotFoundException;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan-version publication — the second half of the fund/plan onboarding pair Task D.0 adds
 * (alongside {@code FundController}). Until now only {@code Scenario.contribute}, calling {@link
 * PlanVersionRepository#publish} in-process, could bring a plan version into existence; on a
 * cluster there was no way to onboard an employer's plan.
 *
 * <p><b>Idempotent on {@code (organizationId, planId, version)}.</b> Replaying the identical
 * request is a {@code 200} carrying the row already on record; the same key with different content
 * is a {@code 409}.
 *
 * <p><b>The fund must exist as of {@code effectiveFrom}.</b> {@code reference.plan_version.fund_id}
 * references {@code reference.fund_identity}, and a plan naming a fund with no version resolvable
 * at that date would otherwise surface as an opaque foreign-key failure — this checks first and
 * answers with a problem detail naming the fund instead.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/plans")
public class PlanVersionController {

  private final PlanVersionRepository planVersions;
  private final FundRepository funds;
  private final OrganizationRepository organizations;
  private final Clock clock;

  public PlanVersionController(
      PlanVersionRepository planVersions,
      FundRepository funds,
      OrganizationRepository organizations,
      Clock clock) {
    this.planVersions = planVersions;
    this.funds = funds;
    this.organizations = organizations;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<PlanVersionResponse> publish(
      @PathVariable("organizationId") String organizationIdRaw,
      @RequestBody PublishPlanVersionRequest request) {
    OrganizationId organizationId = parseOrganizationId(organizationIdRaw);
    if (organizations.findById(organizationId).isEmpty()) {
      throw new OrganizationNotFoundException(organizationId);
    }

    String planId = request.parsedPlanId();
    LocalDate effectiveFrom = request.parsedEffectiveFrom();
    int version = request.parsedVersion();

    Optional<PlanVersion> atRequestedKey =
        planVersions.resolveAsOf(organizationId, planId, effectiveFrom);
    if (isExactKey(atRequestedKey, effectiveFrom, version)) {
      PlanVersion existing = atRequestedKey.orElseThrow();
      if (matchesRequest(existing, request)) {
        return ResponseEntity.ok(PlanVersionResponse.of(existing));
      }
      throw new PlanVersionConflictException(organizationId, planId, version);
    }

    FundId fundId = request.parsedFundId();
    if (funds.resolveAsOf(fundId, effectiveFrom).isEmpty()) {
      throw new FundNotFoundException(fundId, effectiveFrom);
    }

    PlanVersion published;
    try {
      PlanVersion candidate =
          new PlanVersion(
              organizationId,
              planId,
              effectiveFrom,
              version,
              request.parsedEmployerMatchRatioNumerator(),
              request.parsedEmployerMatchRatioDenominator(),
              request.parsedVestingFullYears(),
              request.parsedDealingLagBusinessDays(),
              fundId,
              clock.instant());
      published = planVersions.publish(candidate);
    } catch (IllegalArgumentException e) {
      throw new InvalidPlanVersionRequestException(e.getMessage());
    } catch (DataIntegrityViolationException e) {
      // (organizationId, planId, effectiveFrom, version) was already taken by a version this
      // call's as-of resolution could not see -- resolveAsOf only ever returns the highest
      // version at or before a date, so replaying an earlier version superseded by a later one
      // at the same effectiveFrom lands here. A genuine conflict, not a raw constraint failure.
      throw new PlanVersionConflictException(organizationId, planId, version);
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(PlanVersionResponse.of(published));
  }

  private static boolean isExactKey(
      Optional<PlanVersion> resolved, LocalDate effectiveFrom, int version) {
    return resolved
        .filter(p -> p.effectiveFrom().equals(effectiveFrom) && p.version() == version)
        .isPresent();
  }

  private static boolean matchesRequest(PlanVersion existing, PublishPlanVersionRequest request) {
    return existing.employerMatchRatioNumerator() == request.parsedEmployerMatchRatioNumerator()
        && existing.employerMatchRatioDenominator() == request.parsedEmployerMatchRatioDenominator()
        && existing.vestingFullYears() == request.parsedVestingFullYears()
        && existing.dealingLagBusinessDays() == request.parsedDealingLagBusinessDays()
        && existing.fundId().equals(request.parsedFundId());
  }

  private static OrganizationId parseOrganizationId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidPlanVersionRequestException("organizationId is not a valid UUID");
    }
  }
}
