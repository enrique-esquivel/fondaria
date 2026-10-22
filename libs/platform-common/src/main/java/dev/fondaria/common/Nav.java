package dev.fondaria.common;

import java.util.Objects;

public record Nav(long scaled, CurrencyCode currency) implements Comparable<Nav> {

  public static final long SCALE = 100_000_000L;

  public Nav {
    Objects.requireNonNull(currency, "currency must not be null");
    if (scaled < 0) {
      throw new IllegalArgumentException("Nav cannot be negative, got: " + scaled);
    }
  }

  public static Nav of(long scaled, CurrencyCode currency) {
    return new Nav(scaled, currency);
  }

  public static Money value(Units units, Nav nav) {
    return Money.value(units, nav);
  }

  public Money value(Units units) {
    return Money.value(units, this);
  }

  @Override
  public int compareTo(Nav other) {
    Objects.requireNonNull(other, "other must not be null");
    if (this.currency != other.currency) {
      throw new CurrencyMismatchException(this.currency, other.currency);
    }
    return Long.compare(this.scaled, other.scaled);
  }
}
