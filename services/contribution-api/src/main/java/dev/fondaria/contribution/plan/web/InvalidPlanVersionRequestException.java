package dev.fondaria.contribution.plan.web;

/** A {@code POST .../plans} body that is malformed or violates a domain rule (400). */
public final class InvalidPlanVersionRequestException extends RuntimeException {

  public InvalidPlanVersionRequestException(String message) {
    super(message);
  }
}
