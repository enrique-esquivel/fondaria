package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClusterIdTest {

  @Test
  void ofAndTheCanonicalConstructorAgree() {
    assertThat(ClusterId.of("primary")).isEqualTo(new ClusterId("primary"));
  }

  @Test
  void clustersWithDifferentValuesAreNotEqual() {
    assertThat(ClusterId.of("primary")).isNotEqualTo(ClusterId.of("secondary"));
  }

  @Test
  void rejectsABlankValue() {
    assertThatThrownBy(() -> new ClusterId("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANullValue() {
    assertThatThrownBy(() -> new ClusterId(null)).isInstanceOf(NullPointerException.class);
  }
}
