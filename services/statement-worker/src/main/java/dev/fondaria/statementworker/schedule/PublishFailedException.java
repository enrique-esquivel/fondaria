package dev.fondaria.statementworker.schedule;

import java.io.Serial;

/**
 * RabbitMQ did not confirm one or more {@code statement.jobs.v1} messages within the configured
 * timeout. Transient — unlike {@link StatementFanOutException}, this is retried with bounded
 * backoff by redelivery of the triggering {@code close.signed-off.v1} message, which {@link
 * StatementScheduler} always safely reprocesses.
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
