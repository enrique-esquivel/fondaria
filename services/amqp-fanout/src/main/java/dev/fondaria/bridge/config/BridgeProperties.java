package dev.fondaria.bridge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fan-out service's own configuration, bound from the environment under {@code
 * fondaria.bridge}.
 *
 * @param amqpHost {@code BRIDGE_AMQP_HOST} — RabbitMQ host the bridge consumes from and produces
 *     to. Required.
 * @param prefetch how many unacknowledged {@code bridge.ledger} messages the listener may hold at
 *     once.
 * @param producerConfirmTimeout how long to wait for RabbitMQ to confirm published records before
 *     giving up and letting RabbitMQ redeliver.
 * @param producerRetryBackoff how long to pause after a producer failure before nacking the AMQP
 *     message back onto the queue.
 */
@ConfigurationProperties(prefix = "fondaria.bridge")
public record BridgeProperties(
    String amqpHost, int prefetch, Duration producerConfirmTimeout, Duration producerRetryBackoff) {

  public static final String AMQP_HOST_VARIABLE = "BRIDGE_AMQP_HOST";

  static final String REMEDY =
      "Set the broker connection variable in the environment: "
          + AMQP_HOST_VARIABLE
          + " (the RabbitMQ host). See infrastructure/compose/.env.example.";

  public BridgeProperties {
    prefetch = prefetch <= 0 ? 64 : prefetch;
    producerConfirmTimeout =
        producerConfirmTimeout == null ? Duration.ofSeconds(30) : producerConfirmTimeout;
    producerRetryBackoff =
        producerRetryBackoff == null ? Duration.ofSeconds(1) : producerRetryBackoff;
  }

  /** Fails startup naming the absent variable. Never echoes a value (AGENTS.md §7). */
  public void requireComplete() {
    requirePresent(amqpHost, AMQP_HOST_VARIABLE);
  }

  private static void requirePresent(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}
