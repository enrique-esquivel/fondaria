package dev.fondaria.contribution.fund.web;

/** A {@code POST /v1/funds} body that is malformed or violates a domain rule (400). */
public final class InvalidFundRequestException extends RuntimeException {

  public InvalidFundRequestException(String message) {
    super(message);
  }
}
