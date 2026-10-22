package dev.fondaria.allotmentjob.plan;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Read-only access to {@code reference.plan_version} (phase-02b Task 2b.2).
 *
 * <p>Narrower than {@code rules-engine}'s {@code PlanVersionRepository} on purpose, the same reason
 * {@link dev.fondaria.allotmentjob.fund.FundDirectory} is: that port carries {@code publish}, and
 * this service must never publish a plan version — that is contribution-api's to write. This
 * service only resolves the version in force for a batch's value date, to read its {@code fundId}
 * (ADR-0012), {@code vestingFullYears} and {@code dealingLagBusinessDays}.
 */
public interface PlanVersionDirectory {

  /**
   * The version in force for {@code (organizationId, planId)} on {@code asOfDate}: the highest
   * {@code (effectiveFrom, version)} not after that date. Resolved against the batch's own value
   * date, matching {@code DealingDateService}'s resolution exactly, so this service derives the
   * same fund and the same dealing lag a batch was actually staged under.
   */
  Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate);
}
