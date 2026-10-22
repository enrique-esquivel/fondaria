package dev.fondaria.common;

public enum CurrencyCode {
  MXN(484, 2),
  USD(840, 2);

  private final int numericCode;
  private final int minorUnitExponent;

  CurrencyCode(int numericCode, int minorUnitExponent) {
    this.numericCode = numericCode;
    this.minorUnitExponent = minorUnitExponent;
  }

  public String alphaCode() {
    return name();
  }

  public int numericCode() {
    return numericCode;
  }

  public int minorUnitExponent() {
    return minorUnitExponent;
  }
}
