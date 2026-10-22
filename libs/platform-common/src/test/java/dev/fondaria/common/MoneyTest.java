package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void moneyOfLongMaxValuePlusOneThrowsArithmeticException() {
    Money m1 = Money.of(Long.MAX_VALUE, CurrencyCode.MXN);
    Money m2 = Money.of(1, CurrencyCode.MXN);

    assertThatThrownBy(() -> m1.plus(m2)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void moneyPlusDifferentCurrenciesThrowsCurrencyMismatchException() {
    Money mxn = Money.of(100, CurrencyCode.MXN);
    Money usd = Money.of(100, CurrencyCode.USD);

    assertThatThrownBy(() -> mxn.plus(usd)).isInstanceOf(CurrencyMismatchException.class);
    assertThatThrownBy(() -> mxn.minus(usd)).isInstanceOf(CurrencyMismatchException.class);
    assertThatThrownBy(() -> mxn.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void moneyCanBeNegative() {
    Money negativeMoney = Money.of(-500, CurrencyCode.MXN);

    assertThat(negativeMoney.cents()).isEqualTo(-500);
    assertThat(negativeMoney.isNegative()).isTrue();
    assertThat(negativeMoney.isZero()).isFalse();
  }

  @Test
  void moneyOperationsAndComparison() {
    Money zero = Money.of(0, CurrencyCode.MXN);
    Money pos = Money.of(100, CurrencyCode.MXN);
    Money neg = Money.of(-100, CurrencyCode.MXN);

    assertThat(zero.isZero()).isTrue();
    assertThat(zero.isNegative()).isFalse();
    assertThat(pos.isNegative()).isFalse();

    assertThat(pos.plus(neg)).isEqualTo(zero);
    assertThat(pos.minus(pos)).isEqualTo(zero);
    assertThat(pos.negated()).isEqualTo(neg);
    assertThat(neg.negated()).isEqualTo(pos);

    assertThat(pos.compareTo(zero)).isGreaterThan(0);
    assertThat(neg.compareTo(zero)).isLessThan(0);
    assertThat(zero.compareTo(zero)).isEqualTo(0);
  }

  @Test
  void nullCurrencyThrowsException() {
    assertThatThrownBy(() -> Money.of(100, null)).isInstanceOf(NullPointerException.class);
  }
}
