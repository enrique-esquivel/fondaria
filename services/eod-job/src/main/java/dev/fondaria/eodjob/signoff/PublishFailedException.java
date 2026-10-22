package dev.fondaria.eodjob.signoff;

import java.io.Serial;

/**
 * {@code close.signed-off.v1} could not be produced and confirmed. Transient: the sign-off row is
 * already written, so the next {@link SignoffRunner} sweep re-publishes.
 */
public final class PublishFailedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PublishFailedException(String message) {
    super(message);
  }

  public PublishFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
