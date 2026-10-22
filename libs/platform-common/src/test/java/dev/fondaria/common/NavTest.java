package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NavTest {

  @Test
  void navCannotBeNegativeOrNullCurrency() {
    assertThatThrownBy(() -> Nav.of(-1, CurrencyCode.MXN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Nav.of(100, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void valuationExactSimpleCalculation() {
    // Units 1.5 (150000000), Nav 10.50 (1050000000) => Money 15.75 (1575 cents)
    Units units = Units.of(150_000_000L);
    Nav nav = Nav.of(1_050_000_000L, CurrencyCode.MXN);

    Money result = Money.value(units, nav);
    assertThat(result).isEqualTo(Money.of(1575, CurrencyCode.MXN));

    // Test convenience instance method on Nav as well
    assertThat(nav.value(units)).isEqualTo(Money.of(1575, CurrencyCode.MXN));
  }

  @Test
  void valuationExactHandCalculatedValue() {
    /*
     * Hand-calculated expected value:
     * Units = 3214.882561 -> scaled = 321,488,256,100
     * Nav   = 14.7383      -> scaled = 1,473,830,000
     *
     * Exact mathematical value:
     * 3214.882561 * 14.7383 = 47,381.9036587463 pesos
     * In cents: 47,381.9036587463 * 100 = 4,738,190.36587463 cents
     * Rounding HALF_UP at the final step gives exactly 4,738,190 cents.
     *
     * Computation without overflow:
     * Product of scaled longs: 321,488,256,100 * 1,473,830,000 = 473,819,036,587,463,000,000 (exceeds Long.MAX_VALUE)
     * Division by 10^14 = 4,738,190 with remainder 36,587,463,000,000.
     * 2 * remainder = 73,174,926,000,000 < 10^14, so quotient remains 4,738,190.
     */
    Units units = Units.of(321_488_256_100L);
    Nav nav = Nav.of(1_473_830_000L, CurrencyCode.MXN);

    Money result = Money.value(units, nav);
    assertThat(result).isEqualTo(Money.of(4_738_190, CurrencyCode.MXN));
  }

  @Test
  void navComparison() {
    Nav nav1 = Nav.of(1_000_000_000L, CurrencyCode.MXN);
    Nav nav2 = Nav.of(2_000_000_000L, CurrencyCode.MXN);
    Nav navUsd = Nav.of(1_000_000_000L, CurrencyCode.USD);

    assertThat(nav1.compareTo(nav2)).isLessThan(0);
    assertThatThrownBy(() -> nav1.compareTo(navUsd)).isInstanceOf(CurrencyMismatchException.class);
  }
}
