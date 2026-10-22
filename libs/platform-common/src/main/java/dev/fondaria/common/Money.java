package dev.fondaria.common;

import java.math.BigInteger;
import java.util.Objects;

public record Money(long cents, CurrencyCode currency) implements Comparable<Money> {

  public Money {
    Objects.requireNonNull(currency, "currency must not be null");
  }

  public static Money of(long cents, CurrencyCode currency) {
    return new Money(cents, currency);
  }

  public Money plus(Money other) {
    Objects.requireNonNull(other, "other must not be null");
    checkCurrencyMatch(other);
    return new Money(Math.addExact(this.cents, other.cents), this.currency);
  }

  public Money minus(Money other) {
    Objects.requireNonNull(other, "other must not be null");
    checkCurrencyMatch(other);
    return new Money(Math.subtractExact(this.cents, other.cents), this.currency);
  }

  public Money negated() {
    return new Money(Math.negateExact(this.cents), this.currency);
  }

  public boolean isZero() {
    return this.cents == 0;
  }

  public boolean isNegative() {
    return this.cents < 0;
  }

  @Override
  public int compareTo(Money other) {
    Objects.requireNonNull(other, "other must not be null");
    checkCurrencyMatch(other);
    return Long.compare(this.cents, other.cents);
  }

  public static Money value(Units units, Nav nav) {
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(nav, "nav must not be null");

    BigInteger u = BigInteger.valueOf(units.scaled());
    BigInteger n = BigInteger.valueOf(nav.scaled());
    BigInteger product = u.multiply(n);

    // Combined scale for Units (1e8) and Nav (1e8) is 1e16.
    // Money is in cents (scale 1e2).
    // To get cents, we divide product by 10^(8 + 8 - 2) = 10^14.
    BigInteger divisor = BigInteger.valueOf(100_000_000_000_000L);

    BigInteger[] dr = product.divideAndRemainder(divisor);
    BigInteger quotient = dr[0];
    BigInteger remainder = dr[1];

    // Since units >= 0 and nav >= 0, product is non-negative.
    // HALF_UP rounding: if 2 * remainder >= divisor, round up (add 1).
    if (remainder.multiply(BigInteger.TWO).compareTo(divisor) >= 0) {
      quotient = quotient.add(BigInteger.ONE);
    }

    return new Money(quotient.longValueExact(), nav.currency());
  }

  private void checkCurrencyMatch(Money other) {
    if (this.currency != other.currency) {
      throw new CurrencyMismatchException(this.currency, other.currency);
    }
  }
}
