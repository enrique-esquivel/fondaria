package dev.fondaria.balanceapi.movement;

import java.io.Serial;

/**
 * A continuation cursor did not verify — a {@code 400}.
 *
 * <p>Covers every way a cursor can fail to be one this service issued for this query: a bad
 * encoding, a truncated token, an edited position, a signature that does not match, and a cursor
 * lifted from a different participant's or a different filter's page. All are the same answer on
 * purpose: distinguishing them would tell whoever is editing cursors which part they got wrong.
 *
 * <p>Carries no detail of the offending token, which came from a URL.
 */
public final class TamperedCursorException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public TamperedCursorException(String message) {
    super(message);
  }
}
