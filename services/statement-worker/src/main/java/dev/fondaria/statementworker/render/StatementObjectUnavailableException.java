package dev.fondaria.statementworker.render;

import java.io.Serial;

/**
 * The object store did not accept a rendered statement. Retried by redelivery of the job message.
 */
public final class StatementObjectUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public StatementObjectUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
