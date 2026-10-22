package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.VestingScheduleCalculator;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Discharges Task 2b.3's acceptance criterion 5, deferred by step 4b to step 5 (BACKLOG.md): "a
 * participant enrolled years after their employment start ... retains the earlier employment start
 * date, and a service-based vesting schedule computed from it differs from one computed from the
 * enrolment date."
 *
 * <p>Reuses {@code contribution-file-v2.md} §5's own worked example dates — {@link
 * ContributionFiles#LONG_SERVICE_EMPLOYMENT_START} and {@link
 * ContributionFiles#LONG_SERVICE_ENROLMENT}, both already pinned by {@link
 * ContributionBatchValidatorTest} and the integration suite — rather than inventing a new scenario.
 * {@link VestingScheduleCalculator} itself is exercised more thoroughly, including its edge cases
 * and invariants, by {@code libs/rules-engine}'s own tests; this test asserts exactly the thing the
 * acceptance criterion is owed.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class VestingAssertionTest {

  @Test
  void
      a_participant_enrolled_years_after_employment_start_vests_differently_by_which_date_is_used() {
    PlanVersion plan =
        ContributionFiles.defaultPlanVersion(
            new OrganizationId(UUID.randomUUID()), FundId.of(UUID.randomUUID()));

    long vestedFromEmploymentStart =
        VestingScheduleCalculator.vestedBasisPoints(
            plan, ContributionFiles.LONG_SERVICE_EMPLOYMENT_START, ContributionFiles.VALUE_DATE);
    long vestedFromEnrolment =
        VestingScheduleCalculator.vestedBasisPoints(
            plan, ContributionFiles.LONG_SERVICE_ENROLMENT, ContributionFiles.VALUE_DATE);

    // Seven years of service since employmentStartDate is well past defaultPlanVersion's
    // five-year graded schedule: fully vested. Running the same schedule from enrolmentDate
    // instead — seven months of "service" — is nowhere close. Conflating the two dates would
    // silently vest this participant's employer money years early.
    assertThat(vestedFromEmploymentStart).isEqualTo(10_000L);
    assertThat(vestedFromEnrolment).isLessThan(10_000L);
    assertThat(vestedFromEmploymentStart).isNotEqualTo(vestedFromEnrolment);
  }
}
