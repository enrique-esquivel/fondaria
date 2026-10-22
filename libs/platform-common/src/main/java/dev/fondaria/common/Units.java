package dev.fondaria.common;

import java.util.Objects;

public record Units(long scaled) implements Comparable<Units> {

  public static final long SCALE = 100_000_000L;

  public Units {
    if (scaled < 0) {
      throw new IllegalArgumentException("Units cannot be negative, got: " + scaled);
    }
  }

  public static Units of(long scaled) {
    return new Units(scaled);
  }

  public Units plus(Units other) {
    Objects.requireNonNull(other, "other must not be null");
    return new Units(Math.addExact(this.scaled, other.scaled));
  }

  public Units minus(Units other) {
    Objects.requireNonNull(other, "other must not be null");
    long result = Math.subtractExact(this.scaled, other.scaled);
    if (result < 0) {
      throw new IllegalArgumentException("Units cannot be negative, result would be: " + result);
    }
    return new Units(result);
  }

  public boolean isZero() {
    return this.scaled == 0;
  }

  @Override
  public int compareTo(Units other) {
    Objects.requireNonNull(other, "other must not be null");
    return Long.compare(this.scaled, other.scaled);
  }
}
