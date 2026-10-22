package dev.fondaria.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.CurrencyMismatchException;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NavPerUnitTest {

  private static final Nav OPENING_NAV = Nav.of(10 * Nav.SCALE, CurrencyCode.MXN);

  @Test
  void the_worked_example_strikes_to_the_last_digit() {
    // docs/DOMAIN.md, day 30: fund grown to $10,500, 1,000 units in issue.
    Money reportedValue = Money.of(1_050_000, CurrencyCode.MXN);
    Units unitsInIssue = Units.of(1_000 * Units.SCALE);

    NavPerUnit struck = NavPerUnit.strike(reportedValue, unitsInIssue, OPENING_NAV);

    assertThat(struck.nav()).isEqualTo(Nav.of(1_050_000_000L, CurrencyCode.MXN));
    assertThat(struck.unitsInIssue()).isEqualTo(unitsInIssue);
  }

  @Test
  void a_second_worked_example_also_strikes_to_the_last_digit() {
    // docs/DOMAIN.md, day 60: fund grown to $23,100, 2,000 units in issue.
    Money reportedValue = Money.of(2_310_000, CurrencyCode.MXN);
    Units unitsInIssue = Units.of(2_000 * Units.SCALE);

    NavPerUnit struck = NavPerUnit.strike(reportedValue, unitsInIssue, OPENING_NAV);

    assertThat(struck.nav()).isEqualTo(Nav.of(1_155_000_000L, CurrencyCode.MXN));
  }

  @Test
  void zero_units_in_issue_strikes_at_the_declared_opening_price() {
    Money reportedValue = Money.of(0, CurrencyCode.MXN);

    NavPerUnit struck = NavPerUnit.strike(reportedValue, Units.of(0), OPENING_NAV);

    assertThat(struck.nav()).isEqualTo(OPENING_NAV);
    assertThat(struck.unitsInIssue().isZero()).isTrue();
  }

  @Test
  void a_falling_reported_balance_produces_a_lower_nav_with_nothing_clamping_it() {
    Units unitsInIssue = Units.of(1_000 * Units.SCALE);

    NavPerUnit day30 =
        NavPerUnit.strike(Money.of(1_050_000, CurrencyCode.MXN), unitsInIssue, OPENING_NAV);
    NavPerUnit day45 =
        NavPerUnit.strike(Money.of(900_000, CurrencyCode.MXN), unitsInIssue, OPENING_NAV);
    NavPerUnit day60 =
        NavPerUnit.strike(Money.of(200_000, CurrencyCode.MXN), unitsInIssue, OPENING_NAV);

    assertThat(day45.nav().scaled()).isLessThan(day30.nav().scaled());
    assertThat(day60.nav().scaled()).isLessThan(day45.nav().scaled());
    assertThat(day60.nav()).isEqualTo(Nav.of(200_000_000L, CurrencyCode.MXN));
  }

  @Test
  void a_currency_mismatch_between_reported_value_and_opening_nav_is_rejected() {
    Money reportedValue = Money.of(1_000_000, CurrencyCode.USD);

    assertThatThrownBy(() -> NavPerUnit.strike(reportedValue, Units.of(Units.SCALE), OPENING_NAV))
        .isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void a_negative_reported_value_is_rejected() {
    Money reportedValue = Money.of(-1, CurrencyCode.MXN);

    assertThatThrownBy(() -> NavPerUnit.strike(reportedValue, Units.of(Units.SCALE), OPENING_NAV))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_remainder_is_rounded_half_up_rather_than_truncated() {
    // 200 cents over a divisor of 3: exact quotient is 6_666_666_666_666_666.67 -> rounds up.
    Money reportedValue = Money.of(200, CurrencyCode.MXN);
    Units unitsInIssue = Units.of(3);

    NavPerUnit struck = NavPerUnit.strike(reportedValue, unitsInIssue, OPENING_NAV);

    assertThat(struck.nav().scaled()).isEqualTo(6_666_666_666_666_667L);
  }
}
