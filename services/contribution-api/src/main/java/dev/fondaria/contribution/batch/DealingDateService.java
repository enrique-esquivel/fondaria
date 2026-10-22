package dev.fondaria.contribution.batch;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.rules.DealingDateRule;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The dealing date a staged batch will receive — the valuation date whose NAV strike Phase 21 will
 * price its participants at (Task 21.1).
 *
 * <h2>There is no clock here, and there must not be</h2>
 *
 * <p>This class has no {@code Clock} field and takes no {@code Instant} argument. Every input comes
 * off the batch itself: the value date the employer declared, and {@code stagedAt}, the instant it
 * became dealable. That is what makes the answer stable — asking twice, a week apart, returns the
 * same date — and it is the acceptance criterion Task 21.1 states as a grep: no {@code
 * LocalDate.now()} and no {@code Instant.now()} anywhere in dealing-date logic. {@link
 * DealingDateRule} enforces the same rule on its own side, and {@code NoAmbientClockInRulesTest}
 * makes it a check rather than a convention.
 *
 * <h2>The plan version is resolved as of the value date</h2>
 *
 * <p>Not as of today, and not as of the staging instant. The dealing lag that applies is the one
 * that was in force for the period the money is for — the same as-of date {@link
 * ContributionBatchValidator} already resolves the matching-ratio rule at, so one batch is never
 * validated under one version of its plan and dealt under another.
 */
@Service
public class DealingDateService {

  private final PlanVersionRepository planVersions;
  private final BusinessCalendar calendar;

  public DealingDateService(PlanVersionRepository planVersions, BusinessCalendar calendar) {
    this.planVersions = planVersions;
    this.calendar = calendar;
  }

  /**
   * The valuation date this batch will deal at, or empty when it is not waiting to deal.
   *
   * <p>Empty in two cases, both reported as "no dealing date" rather than as an error:
   *
   * <ul>
   *   <li><b>The batch is not STAGED.</b> A RECEIVED, VALIDATING or PENDING_RECONCILIATION batch
   *       has no dealing date yet, because cut-off has nothing to run against — the money is not
   *       known to be there. A REJECTED batch will never have one. Publishing a speculative date
   *       for these would be promising an employer a price for cash that has not arrived.
   *   <li><b>The batch staged before {@code staged_at} existed, or its plan version cannot be
   *       resolved.</b> The first is the expand/contract window of {@code V13} and empties out as
   *       those batches are allotted. The second is unreachable while {@code reference} stays
   *       append-only — a plan version that resolved at validation time still resolves, because
   *       rows are only ever added — and it returns empty rather than throwing precisely because
   *       this is a status read: a batch whose rulebook has gone missing is a problem for an
   *       operator to investigate, not a reason to fail the employer's status call with a 500.
   * </ul>
   */
  public Optional<LocalDate> dealingDateFor(ContributionBatch batch) {
    if (!batch.isAwaitingAllotment()) {
      return Optional.empty();
    }
    return batch
        .stagedAt()
        .flatMap(
            stagedAt ->
                planVersions
                    .resolveAsOf(
                        batch.organizationId(),
                        batch.declared().planId(),
                        batch.declared().valueDate())
                    .map(PlanVersion::dealingLagBusinessDays)
                    .map(
                        lag ->
                            DealingDateRule.dealingDateFor(
                                batch.declared().valueDate(), stagedAt, lag, calendar)));
  }
}
