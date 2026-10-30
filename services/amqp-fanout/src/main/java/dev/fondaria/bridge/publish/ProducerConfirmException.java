package dev.fondaria.bridge.publish;

import java.io.Serial;

/**
 * RabbitMQ did not confirm one or more published records: a confirm timed out, came back as a nack,
 * or no channel was available. This is <b>transient</b>: the listener must not acknowledge the AMQP
 * messages, and it holds them and publishes them again rather than nacking them (ADR-0041). Nothing
 * is lost — at worst a downstream consumer sees a duplicate, which it tolerates.
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
