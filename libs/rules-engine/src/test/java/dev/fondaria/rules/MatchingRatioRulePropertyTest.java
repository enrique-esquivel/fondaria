package dev.fondaria.rules;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class MatchingRatioRulePropertyTest {

  @Property
  void the_expected_amount_is_never_negative_for_a_non_negative_employee_amount(
      @ForAll @LongRange(min = 0, max = 1_000_000_000L) long employeeCents,
      @ForAll @IntRange(min = 1, max = 1000) int numerator,
      @ForAll @IntRange(min = 1, max = 1000) int denominator) {
    PlanVersion plan = MatchingRatioRuleTest.planWithRatio(numerator, denominator);

    assertThat(MatchingRatioRule.expectedEmployerCents(plan, employeeCents))
        .isGreaterThanOrEqualTo(0L);
  }

  @Property
  void a_matched_amount_never_breaches_its_own_expectation(
      @ForAll @LongRange(min = 0, max = 1_000_000_000L) long employeeCents,
      @ForAll @IntRange(min = 1, max = 1000) int numerator,
      @ForAll @IntRange(min = 1, max = 1000) int denominator) {
    PlanVersion plan = MatchingRatioRuleTest.planWithRatio(numerator, denominator);
    long expected = MatchingRatioRule.expectedEmployerCents(plan, employeeCents);

    assertThat(MatchingRatioRule.breaches(plan, employeeCents, expected)).isFalse();
  }
}
