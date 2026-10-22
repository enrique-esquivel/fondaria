package dev.fondaria.balanceapi.movement;

import java.io.Serial;

/**
 * A query parameter the caller sent is not usable — a {@code 400}.
 *
 * <p><b>The message is authored here, never assembled from the caller's input.</b> Everything on
 * this route arrives in a URL, reaches an access log and may name a person; a problem detail that
 * echoed it back would put it somewhere else again (docs/ENGINEERING-RULES.md §7). So the
 * constructor takes a fixed reason chosen from this codebase's own text, and the offending value is
 * not carried at all.
 */
public final class InvalidMovementQueryException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidMovementQueryException(String reason) {
    super(reason);
  }

  /** The caller-facing detail. Safe by construction — see this class's javadoc. */
  public String reason() {
    return getMessage();
  }
}
