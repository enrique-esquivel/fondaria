package dev.fondaria.statementworker.schedule;

import java.io.Serial;

/**
 * A {@code close.signed-off.v1} message could not be decoded, or names a {@code schemaVersion} this
 * consumer does not recognise. Permanent — never retried, since redelivering the same bytes would
 * fail identically; {@code KafkaConsumeConfiguration} routes it to the container-stopping handler.
 */
public final class MalformedCloseSignedOffEventException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedCloseSignedOffEventException(String message, Throwable cause) {
    super(message, cause);
  }

  public MalformedCloseSignedOffEventException(String message) {
    super(message);
  }
}
