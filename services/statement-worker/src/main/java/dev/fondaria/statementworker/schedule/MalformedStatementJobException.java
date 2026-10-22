package dev.fondaria.statementworker.schedule;

import java.io.Serial;

/**
 * A {@code statement.jobs.v1} message could not be decoded. Treated as poison — dead-lettered and
 * the consumer halted (docs/ENGINEERING-RULES.md §4) — never retried, because retrying the same
 * unparsable bytes never succeeds.
 */
public final class MalformedStatementJobException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedStatementJobException(String message, Throwable cause) {
    super(message, cause);
  }
}
