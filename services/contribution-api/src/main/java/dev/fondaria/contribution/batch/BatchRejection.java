package dev.fondaria.contribution.batch;

import java.util.Map;
import java.util.Objects;

/**
 * Why a whole batch was rejected, in a form safe to return to the employer.
 *
 * @param message human-readable and authored here, never a parser, driver or JDK message — those
 *     carry hostnames, SQL fragments and file contents (AGENTS.md §7)
 * @param details code-specific structured detail, e.g. declared versus actual totals. <b>Every
 *     amount in it is an integer of cents</b>, never a float or a decimal string, matching the
 *     OpenAPI contract. It never contains a participant reference: a rejection body is logged by
 *     everything it passes through, and a whole-batch rejection is about the file, not a person.
 */
public record BatchRejection(BatchRejectionCode code, String message, Map<String, Object> details) {

  public BatchRejection {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(message, "message must not be null");
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public static BatchRejection of(BatchRejectionCode code, String message) {
    return new BatchRejection(code, message, Map.of());
  }

  public static BatchRejection of(
      BatchRejectionCode code, String message, Map<String, Object> details) {
    return new BatchRejection(code, message, details);
  }
}
