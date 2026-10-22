package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundIdTest {

  @Test
  @DisplayName("the id round-trips through its string form")
  void roundTripsThroughString() {
    FundId id = FundId.of(UUID.randomUUID());
    assertThat(FundId.parse(id.toString())).isEqualTo(id);
  }

  @Test
  @DisplayName("two random ids never collide")
  void distinctIdsAreNotEqual() {
    assertThat(FundId.of(UUID.randomUUID())).isNotEqualTo(FundId.of(UUID.randomUUID()));
  }

  @Test
  @DisplayName("a non-UUID fundId is rejected without echoing the input")
  void rejectsMalformedId() {
    assertThatThrownBy(() -> FundId.parse("../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("passwd");
  }

  @Test
  @DisplayName("a null value is rejected")
  void rejectsNullValue() {
    assertThatThrownBy(() -> new FundId(null)).isInstanceOf(NullPointerException.class);
  }
}
