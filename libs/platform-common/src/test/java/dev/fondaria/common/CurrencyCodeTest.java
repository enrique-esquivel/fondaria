package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CurrencyCodeTest {

  @Test
  void currencyCodeProperties() {
    assertThat(CurrencyCode.MXN.alphaCode()).isEqualTo("MXN");
    assertThat(CurrencyCode.MXN.numericCode()).isEqualTo(484);
    assertThat(CurrencyCode.MXN.minorUnitExponent()).isEqualTo(2);

    assertThat(CurrencyCode.USD.alphaCode()).isEqualTo("USD");
    assertThat(CurrencyCode.USD.numericCode()).isEqualTo(840);
    assertThat(CurrencyCode.USD.minorUnitExponent()).isEqualTo(2);
  }
}
