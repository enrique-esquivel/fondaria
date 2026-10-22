package dev.fondaria.balanceapi.summary;

import java.io.Serial;

/**
 * No organization carries the requested id — a {@code 404}.
 *
 * <p><b>Never a zero-filled summary.</b> An aggregate over a roster that does not exist and an
 * aggregate over an employer whose people happen to hold nothing produce the same arithmetic and
 * mean entirely different things; only one of them is an answer. This is checked before any
 * aggregation begins, so the two can never be confused.
 *
 * <p>Carries no value: the id came from a URL, and this exception's message reaches a log
 * (docs/ENGINEERING-RULES.md §7).
 */
public final class OrganizationNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public OrganizationNotFoundException() {
    super("no organization exists for the requested identifier");
  }
}
