package dev.fondaria.statementworker.render;

import java.io.Serial;

/**
 * The completion counter's Redis could not be reached to record a completion. Transient — never
 * added to a Kafka consumer factory's permanent-failure list, so {@code
 * KafkaConsumeConfiguration}'s default routing (bounded backoff, retried forever) applies: the
 * statement object is already written by the time this is thrown, so redelivering the job message
 * only repeats the (idempotent) write and retries the count, and recovers on its own once Redis
 * returns. Swallowing this instead — the earlier design — let a Redis outage acknowledge the job
 * anyway, which silently and permanently under-counted the run.
 */
public final class RedisCounterUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public RedisCounterUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
