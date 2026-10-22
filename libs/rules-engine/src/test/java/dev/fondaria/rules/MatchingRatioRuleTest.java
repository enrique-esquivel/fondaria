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

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MatchingRatioRuleTest {

  static PlanVersion planWithRatio(int numerator, int denominator) {
    return new PlanVersion(
        new OrganizationId(UUID.randomUUID()),
        "PLAN-1",
        LocalDate.of(2026, 1, 1),
        1,
        numerator,
        denominator,
        0,
        0,
        FundId.of(UUID.randomUUID()),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void a_one_to_one_plan_expects_the_employer_to_match_the_employee_exactly() {
    PlanVersion plan = planWithRatio(1, 1);

    assertThat(MatchingRatioRule.expectedEmployerCents(plan, 75_00L)).isEqualTo(75_00L);
    assertThat(MatchingRatioRule.breaches(plan, 75_00L, 75_00L)).isFalse();
    assertThat(MatchingRatioRule.breaches(plan, 75_00L, 74_99L)).isTrue();
    assertThat(MatchingRatioRule.breaches(plan, 75_00L, 75_01L)).isTrue();
  }

  @Test
  void a_fractional_ratio_rounds_half_up_to_the_nearest_cent() {
    PlanVersion plan = planWithRatio(1, 2); // employer matches 50% of employee

    // 333 * 1 / 2 = 166.5 -> rounds up to 167
    assertThat(MatchingRatioRule.expectedEmployerCents(plan, 333L)).isEqualTo(167L);
    // 332 * 1 / 2 = 166 exactly
    assertThat(MatchingRatioRule.expectedEmployerCents(plan, 332L)).isEqualTo(166L);
  }

  @Test
  void zero_employee_contribution_expects_zero_employer_contribution() {
    PlanVersion plan = planWithRatio(1, 1);

    assertThat(MatchingRatioRule.expectedEmployerCents(plan, 0L)).isEqualTo(0L);
    assertThat(MatchingRatioRule.breaches(plan, 0L, 1L)).isTrue();
    assertThat(MatchingRatioRule.breaches(plan, 0L, 0L)).isFalse();
  }
}
