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
 * @param batchSize {@code BRIDGE_AMQP_BATCH_SIZE} — how many {@code bridge.ledger} messages one
 *     consumer handles as a batch: their records are published together, their confirms awaited
 *     together, and the batch is acknowledged with one multiple-ack. At most {@code prefetch}.
 * @param batchWait {@code BRIDGE_AMQP_BATCH_WAIT} — how long a consumer waits for the next message
 *     before handing over a partial batch. Bounds the latency an idle feed adds to one event.
 */
@ConfigurationProperties(prefix = "fondaria.bridge")
public record BridgeProperties(
    String amqpHost,
    int prefetch,
    Duration producerConfirmTimeout,
    Duration producerRetryBackoff,
    int batchSize,
    Duration batchWait) {

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
    batchSize = batchSize <= 0 ? 32 : batchSize;
    batchWait = batchWait == null ? Duration.ofMillis(10) : batchWait;
    if (batchSize > prefetch) {
      throw new ConfigurationException(
          "BRIDGE_AMQP_BATCH_SIZE ("
              + batchSize
              + ") exceeds BRIDGE_AMQP_PREFETCH ("
              + prefetch
              + ")",
          "A consumer can only batch what it holds unacknowledged: lower the batch size or raise"
              + " the prefetch (twice the batch keeps the next batch arriving while one publishes).");
    }
    if (batchWait.isNegative() || batchWait.isZero()) {
      throw new ConfigurationException(
          "BRIDGE_AMQP_BATCH_WAIT must be positive", "Set it to a duration such as 10ms.");
    }
  }

  /**
   * Fails startup naming the absent variable. Never echoes a value (docs/ENGINEERING-RULES.md §7).
   */
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
