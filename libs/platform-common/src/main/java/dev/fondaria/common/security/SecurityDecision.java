package dev.fondaria.common.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record representing an auditable security access control decision.
 *
 * <p>Captures who made the request, what resource was accessed, the HTTP action/method, whether
 * access was permitted or denied, and the rationale.
 */
public record SecurityDecision(
    String subject,
    String resource,
    String action,
    Outcome outcome,
    String reason,
    Instant timestamp) {

  public enum Outcome {
    PERMITTED,
    DENIED
  }

  public SecurityDecision {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(timestamp, "timestamp");
  }

  public static SecurityDecision permitted(
      String subject, String resource, String action, String reason) {
    return new SecurityDecision(
        subject, resource, action, Outcome.PERMITTED, reason, Instant.now());
  }

  public static SecurityDecision denied(
      String subject, String resource, String action, String reason) {
    return new SecurityDecision(subject, resource, action, Outcome.DENIED, reason, Instant.now());
  }
}
