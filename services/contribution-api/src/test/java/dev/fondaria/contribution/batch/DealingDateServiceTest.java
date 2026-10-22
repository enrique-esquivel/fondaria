package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.plan.InMemoryPlanVersionRepository;
import dev.fondaria.rules.PlanVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link DealingDateService} wires {@link dev.fondaria.rules.DealingDateRule} to a batch: which
 * plan version's lag applies, and which statuses even have a dealing date. {@code
 * DealingDateRuleTest} (libs/rules-engine) owns the calendar arithmetic itself.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DealingDateServiceTest {

  private static final ZoneOffset UTC = ZoneOffset.UTC;
  private static final String PLAN_ID = "PLAN-1";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final InMemoryPlanVersionRepository planVersions = new InMemoryPlanVersionRepository();
  private final BusinessCalendar calendar =
      new BusinessCalendar(UTC, LocalTime.of(18, 0), Set.of());
  private final DealingDateService service = new DealingDateService(planVersions, calendar);

  @Test
  void a_staged_batch_deals_at_the_date_its_plans_lag_and_value_date_produce() {
    planVersions.publish(planVersion(2));
    LocalDate valueDate = LocalDate.of(2026, 3, 11); // Wednesday
    ContributionBatch batch = stagedBatch(valueDate, valueDate.atTime(9, 0).toInstant(UTC));

    assertThat(service.dealingDateFor(batch)).contains(LocalDate.of(2026, 3, 13));
  }

  @Test
  void a_batch_not_yet_staged_has_no_dealing_date() {
    planVersions.publish(planVersion(0));
    LocalDate valueDate = LocalDate.of(2026, 3, 11);
    ContributionBatch received =
        new ContributionBatch(
            UUID.randomUUID(),
            organizationId,
            "key",
            "0".repeat(64),
            ContributionBatchStatus.RECEIVED,
            declaration(valueDate),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            valueDate.atStartOfDay().toInstant(UTC),
            valueDate.atStartOfDay().toInstant(UTC));

    assertThat(service.dealingDateFor(received)).isEmpty();
  }

  @Test
  void a_rejected_batch_has_no_dealing_date() {
    LocalDate valueDate = LocalDate.of(2026, 3, 11);
    ContributionBatch rejected =
        new ContributionBatch(
            UUID.randomUUID(),
            organizationId,
            "key",
            "0".repeat(64),
            ContributionBatchStatus.REJECTED,
            declaration(valueDate),
            Optional.of(BatchRejection.of(BatchRejectionCode.MALFORMED_FILE, "bad file")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            valueDate.atStartOfDay().toInstant(UTC),
            valueDate.atStartOfDay().toInstant(UTC));

    assertThat(service.dealingDateFor(rejected)).isEmpty();
  }

  /**
   * The expand/contract window of {@code V13}: a batch staged before the column existed carries
   * {@code stagedAt = Optional.empty()} even though its status is STAGED. Returning empty here
   * rather than guessing an instant is the honest answer for a row this migration cannot retrofit.
   */
  @Test
  void a_staged_batch_with_no_recorded_staged_instant_has_no_dealing_date() {
    planVersions.publish(planVersion(0));
    LocalDate valueDate = LocalDate.of(2026, 3, 11);
    ContributionBatch staged =
        new ContributionBatch(
            UUID.randomUUID(),
            organizationId,
            "key",
            "0".repeat(64),
            ContributionBatchStatus.STAGED,
            declaration(valueDate),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            valueDate.atStartOfDay().toInstant(UTC),
            valueDate.atStartOfDay().toInstant(UTC));

    assertThat(service.dealingDateFor(staged)).isEmpty();
  }

  /** No plan version can resolve for this batch's value date — reported as unknown, not thrown. */
  @Test
  void a_staged_batch_whose_plan_version_cannot_resolve_has_no_dealing_date() {
    LocalDate valueDate = LocalDate.of(2026, 3, 11);
    ContributionBatch batch = stagedBatch(valueDate, valueDate.atTime(9, 0).toInstant(UTC));

    assertThat(service.dealingDateFor(batch)).isEmpty();
  }

  private ContributionBatch stagedBatch(LocalDate valueDate, Instant stagedAt) {
    return new ContributionBatch(
        UUID.randomUUID(),
        organizationId,
        "key",
        "0".repeat(64),
        ContributionBatchStatus.STAGED,
        declaration(valueDate),
        Optional.empty(),
        Optional.of(stagedAt),
        Optional.empty(),
        Optional.of(stagedAt),
        valueDate.atStartOfDay().toInstant(UTC),
        stagedAt);
  }

  private BatchDeclaration declaration(LocalDate valueDate) {
    return new BatchDeclaration(
        PLAN_ID,
        "2026-03",
        valueDate,
        100_000L,
        1,
        java.util.Map.of(ContributionSource.EMPLOYEE, 100_000L));
  }

  private PlanVersion planVersion(int dealingLagBusinessDays) {
    return new PlanVersion(
        organizationId,
        PLAN_ID,
        LocalDate.of(2020, 1, 1),
        1,
        1,
        1,
        0,
        dealingLagBusinessDays,
        FundId.of(UUID.randomUUID()),
        Instant.parse("2020-01-01T00:00:00Z"));
  }
}
