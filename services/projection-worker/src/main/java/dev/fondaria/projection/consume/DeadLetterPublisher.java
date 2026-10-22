package dev.fondaria.projection.consume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.projection.config.ProjectionConsumerProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes one record to {@code ledger.entries.dlq} preserving a message this consumer could not
 * decode, so the bytes survive for a human before the consumer halts (Task 5.3, ADR-0022).
 *
 * <p>The envelope names the source coordinates — queue, exchange, routing key — and the decode
 * failure's reason, and carries the original payload as UTF-8 text. It does <b>not</b> carry any
 * decoded field: the whole point is that decoding failed.
 *
 * <p>If the write itself does not confirm within the configured timeout, this returns {@code false}
 * rather than throwing: the caller halts regardless.
 */
@Component
public class DeadLetterPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterPublisher.class);

  static final String REASON_UNKNOWN_VERSION = "UNKNOWN_SCHEMA_VERSION";
  static final String REASON_MALFORMED = "MALFORMED_EVENT";

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String deadLetterQueue;
  private final long confirmTimeoutMillis;
  private final RabbitMetrics metrics;

  public DeadLetterPublisher(
      RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper,
      Clock clock,
      ProjectionConsumerProperties properties,
      RabbitMetrics metrics) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.deadLetterQueue = properties.deadLetterQueue();
    this.confirmTimeoutMillis = properties.deadLetterTimeout().toMillis();
    this.metrics = metrics;
  }

  /**
   * @return {@code true} if RabbitMQ confirmed the dead-letter write, {@code false} if it did not
   *     within the timeout or failed — in which case the caller still halts.
   */
  public boolean publish(Message message, String reason, String detail) {
    String sourceExchange = message.getMessageProperties().getReceivedExchange();
    String sourceQueue = message.getMessageProperties().getConsumerQueue();
    String sourceKey = message.getMessageProperties().getReceivedRoutingKey();
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    Map<String, Object> envelope =
        Map.of(
            "schemaVersion",
            LedgerTopics.SCHEMA_VERSION,
            "reason",
            reason,
            "detail",
            detail == null ? "" : detail,
            "sourceTopic",
            sourceExchange != null ? sourceExchange : (sourceQueue != null ? sourceQueue : ""),
            "sourcePartition",
            0,
            "sourceOffset",
            deliveryTag,
            "sourceKey",
            sourceKey == null ? "" : sourceKey,
            "observedAt",
            clock.instant().toString(),
            "payload",
            message.getBody() == null ? "" : new String(message.getBody(), StandardCharsets.UTF_8));
    String json;
    try {
      json = objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      LOGGER.error("could not serialise the dead-letter envelope: {}", e.getMessage());
      return false;
    }

    if (confirmTimeoutMillis <= 0) {
      return false;
    }

    long start = System.nanoTime();
    try {
      CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
      rabbitTemplate.convertAndSend("", deadLetterQueue, json, correlationData);
      CorrelationData.Confirm confirm =
          correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      if (confirm != null && confirm.isAck()) {
        metrics.publishCompleted(deadLetterQueue, System.nanoTime() - start, false);
        LOGGER.error(
            "poison message from {} preserved to {} ({})", sourceQueue, deadLetterQueue, reason);
        return true;
      } else {
        String nackReason = confirm != null ? confirm.getReason() : "no confirm returned";
        metrics.publishCompleted(deadLetterQueue, System.nanoTime() - start, true);
        LOGGER.error(
            "RabbitMQ nacked dead-letter write to {} from {}: {}",
            deadLetterQueue,
            sourceQueue,
            nackReason);
        return false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.publishCompleted(deadLetterQueue, System.nanoTime() - start, true);
      return false;
    } catch (ExecutionException | TimeoutException e) {
      metrics.publishCompleted(deadLetterQueue, System.nanoTime() - start, true);
      LOGGER.error(
          "could not write poison message from {} to {}: {}",
          sourceQueue,
          deadLetterQueue,
          e.getMessage());
      return false;
    }
  }
}
