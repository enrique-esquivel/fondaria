package dev.fondaria.bridge.publish;

import java.io.Serial;

/**
 * Redpanda did not confirm one or more produced records within the timeout. This is
 * <b>transient</b>: the listener must not acknowledge the AMQP message, so RabbitMQ redelivers it
 * and the write is retried (phase-04-cdc.md Task 4.3: "producer failure does not ack the AMQP
 * message"). Nothing is lost — at worst a downstream consumer sees a duplicate, which it tolerates.
 */
public final class ProducerConfirmException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ProducerConfirmException(String message) {
    super(message);
  }

  public ProducerConfirmException(String message, Throwable cause) {
    super(message, cause);
  }
}
