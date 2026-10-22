package dev.fondaria.balanceapi.web;

import java.io.Serial;

/**
 * The {@code date} path segment is not an ISO-8601 calendar date.
 *
 * <p>A distinct type for the same reason {@link MalformedParticipantIdException} is one: {@code
 * LocalDate.parse} raises {@link java.time.format.DateTimeParseException}, and matching that
 * broadly would let a parse failure anywhere downstream be reported as the caller's malformed date.
 *
 * <p>Carries no value: the offending text came from a URL and must not reach a problem detail or a
 * log (AGENTS.md §7). A date is not itself sensitive, but it arrives as raw caller input and
 * reflecting raw input into an error body is how a problem detail becomes an injection surface.
 */
public final class MalformedDateException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedDateException(Throwable cause) {
    super("date is not a valid ISO-8601 date", cause);
  }
}
