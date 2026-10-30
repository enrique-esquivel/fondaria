package dev.fondaria.bridge.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.transform.LedgerRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Produces records to RabbitMQ exchanges and blocks until every one is confirmed.
 *
 * <p>This is the half of the fan-out service that makes "ack only after the publisher confirms"
 * real: {@link #publishAll} returns only when RabbitMQ has acknowledged every record, and throws
 * {@link ProducerConfirmException} otherwise. The listener does not acknowledge the AMQP message
 * until this returns normally.
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
    publishBatch(records, List.of());
  }

  /**
   * {@link #publishAll} for a whole batch of change events: every record, and every poison
   * message's DLQ envelope, is sent before any confirm is awaited, so a batch of N events costs one
   * confirm wait rather than N. Returns only when RabbitMQ has confirmed all of them; throws {@link
   * ProducerConfirmException} if any was refused or timed out — the caller then acknowledges
   * nothing, and the records already confirmed are published again on the retry, which every
   * consumer absorbs (idempotent on {@code transfer_id}).
   *
   * <p><b>One channel per batch, and it never goes back to the cache owing confirms.</b> Each bare
   * {@code rabbitTemplate.send} checks out a channel of its own, and with correlated confirms
   * Spring parks a channel that still has unconfirmed publishes outside the cache until they
   * arrive, so the next send opens another one. While confirms were slow that was one channel per
   * record: on load run 8 one connection reached RabbitMQ's 2047-channel limit and every publish
   * failed. Now the whole batch runs inside one {@link RabbitTemplate#invoke} scope, its confirms
   * are awaited inside that scope against one deadline, and a batch that is not fully confirmed
   * closes its channel instead of returning it. The publisher connection's checkout limit ({@code
   * BridgeConfiguration#boundedPublisherConnection}) counts a closing channel until it is gone, so
   * the channel count is bounded by the listener concurrency (ADR-0041).
   */
  public void publishBatch(List<LedgerRecord> records, List<DlqEnvelope> deadLetters) {
    List<Outbound> outbound = new ArrayList<>(records.size() + deadLetters.size());
    for (LedgerRecord record : records) {
      outbound.add(new Outbound(record.topic(), record.key(), serialize(record.payload())));
    }
    for (DlqEnvelope envelope : deadLetters) {
      outbound.add(new Outbound("", LedgerTopics.ENTRIES_DLQ, serialize(envelope)));
    }
    if (outbound.isEmpty()) {
      return;
    }
    Optional<ProducerConfirmException> failure;
    try {
      failure = rabbitTemplate.invoke(ops -> publishOnOneChannel(ops, outbound));
    } catch (AmqpException e) {
      // No channel to publish on: the broker is unreachable, or every permit is held by a channel
      // still closing (the checkout timeout). Transient, like an unconfirmed publish.
      metrics.publishCompleted(outbound.get(0).destination(), 0L, true);
      throw new ProducerConfirmException(
          "RabbitMQ gave the publisher no channel: " + e.getMessage(), e);
    } finally {
      RabbitUtils.clearPhysicalCloseRequired();
    }
    if (failure.isPresent()) {
      throw failure.get();
    }
  }

  private Optional<ProducerConfirmException> publishOnOneChannel(
      RabbitOperations ops, List<Outbound> outbound) {
    List<Pending> pending = new ArrayList<>(outbound.size());
    ProducerConfirmException failure = null;
    try {
      for (Outbound o : outbound) {
        pending.add(send(ops, o));
      }
    } catch (ProducerConfirmException e) {
      failure = e;
    }
    long deadline = System.nanoTime() + confirmTimeout.toNanos();
    for (Pending p : pending) {
      try {
        awaitConfirm(p, deadline);
      } catch (ProducerConfirmException e) {
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      // Leaving the invoke scope closes this channel. Returned to the cache it would sit outside
      // it until its missing confirms arrive, which on a struggling broker is never soon.
      ops.execute(
          channel -> {
            RabbitUtils.setPhysicalCloseRequired(channel, true);
            return Boolean.TRUE;
          });
    }
    return Optional.ofNullable(failure);
  }

  /** A record serialised and addressed, not yet handed to RabbitMQ. */
  private record Outbound(String exchange, String routingKey, String payload) {
    /**
     * The default exchange ("") routes purely by routing key, so that is the only name that
     * identifies the destination — tagging on the exchange alone would report every one of those
     * publishes (the DLQ, notably) as an empty label.
     */
    String destination() {
      return exchange.isEmpty() ? routingKey : exchange;
    }
  }

  /** A record handed to RabbitMQ whose confirm has not been read yet. */
  private record Pending(
      String exchange, String destination, CorrelationData correlationData, long startNanos) {}

  private Pending send(RabbitOperations ops, Outbound outbound) {
    String exchange = outbound.exchange();
    String destination = outbound.destination();
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
        MessageBuilder.withBody(outbound.payload().getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    String routingKey = outbound.routingKey() == null ? "" : outbound.routingKey();
    try {
      ops.send(exchange, routingKey, message, correlationData);
    } catch (Exception e) {
      metrics.publishCompleted(destination, System.nanoTime() - start, true);
      throw new ProducerConfirmException(
          "RabbitMQ did not accept record for exchange " + exchange + ": " + e.getMessage(), e);
    }
    return new Pending(exchange, destination, correlationData, start);
  }

  /**
   * Waits for one confirm until the batch's deadline. One deadline for the whole batch: awaiting
   * each record for the full timeout would let a dead broker hold a batch of 96 for 96 timeouts.
   */
  private void awaitConfirm(Pending pending, long deadlineNanos) {
    String exchange = pending.exchange();
    String destination = pending.destination();
    long start = pending.startNanos();
    try {
      CorrelationData.Confirm confirm =
          pending
              .correlationData()
              .getFuture()
              .get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
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
