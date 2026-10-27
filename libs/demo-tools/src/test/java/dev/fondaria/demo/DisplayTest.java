package dev.fondaria.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisplayTest {

  @Test
  void pesosAreSignedAndNeverClamped() {
    assertThat(Display.pesos(0)).isEqualTo("$0.00");
    assertThat(Display.pesos(5)).isEqualTo("$0.05");
    assertThat(Display.pesos(123_456_789)).isEqualTo("$1,234,567.89");
    assertThat(Display.pesos(-228_212)).isEqualTo("-$2,282.12");
  }

  @Test
  void countsAndHashes() {
    assertThat(Display.count(300_000)).isEqualTo("300,000");
    assertThat(Display.shortHash("0123456789abcdef")).isEqualTo("0123456789ab…");
    assertThat(Display.shortHash("abc")).isEqualTo("abc");
  }
}
