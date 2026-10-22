package dev.fondaria.statementworker.render;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Writes one record to {@code statement.jobs.dlq} preserving a job this consumer could not process
 * — {@link ParticipantNotEnrolledException} today, an unsupported {@code schemaVersion} or a
 * malformed message tomorrow — so the bytes survive for a human before the consumer halts.
 *
 * <p>The envelope names the source coordinates and the failure's reason, and carries the original
 * payload. <b>It carries the payload as-is, which already names {@code organizationId} and {@code
 * participantInternalRef}</b> — the same information {@code workflow.allotment} and the snapshot
 * file already store, not a new exposure — but the {@code reason} and {@code detail} fields written
 * here must never additionally echo either value into free text, and neither this class's own log
 * line does.
 */
@Component
public class DeadLetterPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterPublisher.class);

  public static final String TOPIC = "statement.jobs.dlq";
  public static final String REASON_PARTICIPANT_NOT_ENROLLED = "PARTICIPANT_NOT_ENROLLED";
  public static final String REASON_MALFORMED = "MALFORMED_JOB";

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RabbitMetrics metrics;

  public DeadLetterPublisher(
      RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper,
      Clock clock,
      RabbitMetrics metrics) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * @return {@code true} if RabbitMQ confirmed the write; the caller halts regardless.
   */
  public boolean publish(Message message, String reason, String detail) {
    String payload =
        message.getBody() != null ? new String(message.getBody(), StandardCharsets.UTF_8) : "";
    String queue = message.getMessageProperties().getConsumerQueue();
    String exchange = message.getMessageProperties().getReceivedExchange();
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    Map<String, Object> envelope =
        Map.of(
            "reason",
            reason,
            "detail",
            detail == null ? "" : detail,
            "sourceQueue",
            queue == null ? "" : queue,
            "sourceExchange",
            exchange == null ? "" : exchange,
            "deliveryTag",
            deliveryTag,
            "observedAt",
            clock.instant().toString(),
            "payload",
            payload);
    String json;
    try {
      json = objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      LOGGER.error("could not serialise the dead-letter envelope: {}", e.getMessage());
      return false;
    }
    long start = System.nanoTime();
    try {
      CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
      rabbitTemplate.convertAndSend(TOPIC, TOPIC, json, correlationData);
      CorrelationData.Confirm confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
      if (confirm == null || !confirm.isAck()) {
        String confirmReason = confirm != null ? confirm.getReason() : "null";
        metrics.publishCompleted(TOPIC, System.nanoTime() - start, true);
        LOGGER.error(
            "could not write poison job from {} deliveryTag {} to {}: nacked ({})",
            queue,
            deliveryTag,
            TOPIC,
            confirmReason);
        return false;
      }
      metrics.publishCompleted(TOPIC, System.nanoTime() - start, false);
      LOGGER.error(
          "poison job from {} deliveryTag {} preserved to {} ({})",
          queue,
          deliveryTag,
          TOPIC,
          reason);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.publishCompleted(TOPIC, System.nanoTime() - start, true);
      return false;
    } catch (ExecutionException | TimeoutException e) {
      metrics.publishCompleted(TOPIC, System.nanoTime() - start, true);
      LOGGER.error(
          "could not write poison job from {} deliveryTag {} to {}: {}",
          queue,
          deliveryTag,
          TOPIC,
          e.getMessage());
      return false;
    }
  }
}
