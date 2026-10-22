package dev.fondaria.common;

public class CurrencyMismatchException extends RuntimeException {

  public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
    super("Currency mismatch: expected " + expected + ", got " + actual);
  }

  public CurrencyMismatchException(String message) {
    super(message);
  }
}
