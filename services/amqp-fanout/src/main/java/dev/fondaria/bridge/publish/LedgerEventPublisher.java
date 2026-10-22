package dev.fondaria.bridge.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.transform.LedgerRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Produces records to RabbitMQ exchanges and blocks until every one is confirmed.
 *
 * <p>This is the half of the fan-out service that makes "ack only after the publisher confirms"
 * real: {@link #publishAll} returns only when RabbitMQ has acknowledged all three records, and
 * throws {@link ProducerConfirmException} otherwise. The listener does not acknowledge the AMQP
 * message until this returns normally.
 */
@Component
public class LedgerEventPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(LedgerEventPublisher.class);

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper mapper;
  private final Duration confirmTimeout;
  private final RabbitMetrics metrics;

  public LedgerEventPublisher(
      RabbitTemplate rabbitTemplate,
      ObjectMapper mapper,
      BridgeProperties properties,
      RabbitMetrics metrics) {
    this.rabbitTemplate = rabbitTemplate;
    this.mapper = mapper;
    this.confirmTimeout = properties.producerConfirmTimeout();
    this.metrics = metrics;
  }

  /**
   * Produces every record and blocks until all are confirmed, or throws {@link
   * ProducerConfirmException}. All records are sent first and their confirms awaited together, so
   * one change event costs one broker round trip rather than one per record. Nothing is
   * acknowledged to the caller until every confirm is in, exactly as before.
   */
  public void publishAll(List<LedgerRecord> records) {
    List<Pending> pending = new ArrayList<>(records.size());
    ProducerConfirmException failure = null;
    for (LedgerRecord record : records) {
      try {
        pending.add(send(record.topic(), record.key(), serialize(record.payload())));
      } catch (ProducerConfirmException e) {
        failure = e;
        break;
      }
    }
    for (Pending p : pending) {
      try {
        awaitConfirm(p);
      } catch (ProducerConfirmException e) {
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Produces one keyless record to the DLQ and blocks until it is confirmed. */
  public void publishDlq(DlqEnvelope envelope) {
    awaitConfirm(send("", LedgerTopics.ENTRIES_DLQ, serialize(envelope)));
  }

  /** A record handed to RabbitMQ whose confirm has not been read yet. */
  private record Pending(
      String exchange, String destination, CorrelationData correlationData, long startNanos) {}

  private Pending send(String exchange, String routingKey, String payload) {
    // The default exchange ("") routes purely by routing key, so that is the only name that
    // identifies the destination — tagging on the exchange alone would report every one of
    // those publishes (the DLQ, notably) as an empty label.
    String destination = exchange.isEmpty() ? routingKey : exchange;
    long start = System.nanoTime();
    if (confirmTimeout.isZero()) {
      metrics.publishCompleted(destination, System.nanoTime() - start, true);
      throw new ProducerConfirmException(
          "RabbitMQ did not confirm record published to exchange "
              + exchange
              + " within "
              + confirmTimeout
              + ": forced confirmation timeout");
    }
    CorrelationData correlationData = new CorrelationData();
    Message message =
        MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    try {
      rabbitTemplate.send(exchange, routingKey == null ? "" : routingKey, message, correlationData);
    } catch (Exception e) {
      metrics.publishCompleted(destination, System.nanoTime() - start, true);
      throw new ProducerConfirmException(
          "RabbitMQ did not accept record for exchange " + exchange + ": " + e.getMessage(), e);
    }
    return new Pending(exchange, destination, correlationData, start);
  }

  private void awaitConfirm(Pending pending) {
    String exchange = pending.exchange();
    String destination = pending.destination();
    long start = pending.startNanos();
    try {
      CorrelationData.Confirm confirm =
          pending
              .correlationData()
              .getFuture()
              .get(confirmTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      if (confirm == null || !confirm.isAck()) {
        String reason = confirm != null ? confirm.getReason() : "null confirm";
        metrics.publishCompleted(destination, System.nanoTime() - start, true);
        throw new ProducerConfirmException(
            "RabbitMQ nacked record published to exchange " + exchange + ": " + reason);
      }
      metrics.publishCompleted(destination, System.nanoTime() - start, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.publishCompleted(destination, System.nanoTime() - start, true);
      throw new ProducerConfirmException("interrupted while awaiting RabbitMQ confirmation", e);
    } catch (Exception e) {
      if (e instanceof ProducerConfirmException pce) {
        throw pce;
      }
      metrics.publishCompleted(destination, System.nanoTime() - start, true);
      throw new ProducerConfirmException(
          "RabbitMQ did not confirm record published to exchange "
              + exchange
              + " within "
              + confirmTimeout
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private String serialize(Object payload) {
    try {
      return mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialise a bridge event", e);
    }
  }
}
