package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UnitsTest {

  @Test
  void unitsOfNegativeValueThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> Units.of(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Units(-100)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unitsOperations() {
    Units u1 = Units.of(150_000_000L); // 1.5 units
    Units u2 = Units.of(50_000_000L); // 0.5 units

    assertThat(u1.plus(u2)).isEqualTo(Units.of(200_000_000L));
    assertThat(u1.minus(u2)).isEqualTo(Units.of(100_000_000L));
    assertThat(Units.of(0).isZero()).isTrue();
    assertThat(u1.isZero()).isFalse();
  }

  @Test
  void unitsMinusResultingInNegativeThrowsIllegalArgumentException() {
    Units u1 = Units.of(50_000_000L);
    Units u2 = Units.of(100_000_000L);

    assertThatThrownBy(() -> u1.minus(u2)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unitsComparison() {
    Units u1 = Units.of(100_000_000L);
    Units u2 = Units.of(200_000_000L);

    assertThat(u1.compareTo(u2)).isLessThan(0);
    assertThat(u2.compareTo(u1)).isGreaterThan(0);
    assertThat(u1.compareTo(u1)).isEqualTo(0);
  }
}
