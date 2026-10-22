package dev.fondaria.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Reuses {@code contribution-file-v2.md} §5's worked-example dates for participant P-001 — employed
 * 2019-03-01, enrolled 2026-01-15 — to make the assertion phase-02b Task 2b.3's acceptance
 * criterion 5 owes: a service-based schedule computed from the employment start date differs from
 * one computed from the enrolment date, and the earlier date is the one that reaches full vesting
 * first.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class VestingScheduleCalculatorTest {

  private static final LocalDate EMPLOYMENT_START = LocalDate.of(2019, 3, 1);
  private static final LocalDate ENROLMENT = LocalDate.of(2026, 1, 15);
  private static final LocalDate AS_OF =
      LocalDate.of(2026, 8, 20); // the worked example's value date

  private static PlanVersion planWithVestingFullYears(int years) {
    return new PlanVersion(
        new OrganizationId(UUID.randomUUID()),
        "PLAN-1",
        LocalDate.of(2026, 1, 1),
        1,
        1,
        1,
        years,
        0,
        FundId.of(UUID.randomUUID()),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void a_participant_enrolled_years_after_employment_start_is_fully_vested_from_that_date() {
    PlanVersion plan = planWithVestingFullYears(5);

    long vestedFromEmploymentStart =
        VestingScheduleCalculator.vestedBasisPoints(plan, EMPLOYMENT_START, AS_OF);
    long vestedFromEnrolment = VestingScheduleCalculator.vestedBasisPoints(plan, ENROLMENT, AS_OF);

    // Over seven years of service since employmentStartDate, a five-year graded schedule is
    // already at 100% — the case that silently misallocates money if enrolmentDate is used
    // instead.
    assertThat(vestedFromEmploymentStart).isEqualTo(10_000L);
    // Only a few months elapsed since enrolmentDate: nowhere near fully vested.
    assertThat(vestedFromEnrolment).isLessThan(10_000L);
    assertThat(vestedFromEmploymentStart).isNotEqualTo(vestedFromEnrolment);
  }

  @Test
  void immediate_vesting_is_always_fully_vested_once_service_has_started() {
    PlanVersion plan = planWithVestingFullYears(0);

    assertThat(
            VestingScheduleCalculator.vestedBasisPoints(plan, EMPLOYMENT_START, EMPLOYMENT_START))
        .isEqualTo(10_000L);
    assertThat(
            VestingScheduleCalculator.vestedBasisPoints(
                plan, EMPLOYMENT_START, EMPLOYMENT_START.minusDays(1)))
        .isEqualTo(0L);
  }

  @Test
  void vesting_never_exceeds_full_once_the_schedule_completes() {
    PlanVersion plan = planWithVestingFullYears(5);

    assertThat(
            VestingScheduleCalculator.vestedBasisPoints(
                plan, EMPLOYMENT_START, EMPLOYMENT_START.plusYears(50)))
        .isEqualTo(10_000L);
  }

  @Test
  void vesting_before_service_has_started_is_zero() {
    PlanVersion plan = planWithVestingFullYears(5);

    assertThat(
            VestingScheduleCalculator.vestedBasisPoints(
                plan, EMPLOYMENT_START, EMPLOYMENT_START.minusDays(1)))
        .isEqualTo(0L);
  }
}
