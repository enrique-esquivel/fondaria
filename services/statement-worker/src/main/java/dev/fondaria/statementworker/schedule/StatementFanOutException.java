package dev.fondaria.statementworker.schedule;

import java.io.Serial;

/**
 * The month-end fan-out found the platform's data in a state Task 7.5b's design does not expect,
 * and must not guess past. Thrown rather than logged-and-continued, because every case this is
 * raised for would otherwise generate a wrong or incomplete set of statements silently.
 *
 * <p><b>Permanent, unlike {@link PublishFailedException}.</b> A position-count mismatch or a
 * participant holding more than one fund is a data-integrity problem that redelivering the
 * triggering event will reproduce identically — it needs a human, not a retry — so {@code
 * KafkaConsumeConfiguration} routes this to the container-stopping handler rather than bounded
 * backoff.
 */
public final class StatementFanOutException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public StatementFanOutException(String message) {
    super(message);
  }

  public StatementFanOutException(String message, Throwable cause) {
    super(message, cause);
  }
}
