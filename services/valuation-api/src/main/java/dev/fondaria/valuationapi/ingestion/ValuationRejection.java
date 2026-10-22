package dev.fondaria.valuationapi.ingestion;

import java.util.Map;
import java.util.Objects;

/**
 * Why a whole valuation batch was rejected, in a form safe to return to the provider.
 *
 * @param message human-readable and authored here, never a parser, driver or SDK message — those
 *     carry hostnames, SQL fragments and file contents (AGENTS.md §7)
 * @param details code-specific structured detail: the fund and valuation date at fault, declared
 *     versus actual row counts. <b>Every amount in it is an integer of cents</b>, never a
 *     fractional or decimal string. A fund id and a valuation date are safe to include — neither
 *     identifies a person, and nothing in a valuation file does (unlike a contribution file, which
 *     is why this record may name its subject where {@code BatchRejection} may not).
 */
public record ValuationRejection(
    ValuationRejectionCode code, String message, Map<String, Object> details) {

  public ValuationRejection {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(message, "message must not be null");
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public static ValuationRejection of(ValuationRejectionCode code, String message) {
    return new ValuationRejection(code, message, Map.of());
  }

  public static ValuationRejection of(
      ValuationRejectionCode code, String message, Map<String, Object> details) {
    return new ValuationRejection(code, message, details);
  }
}
