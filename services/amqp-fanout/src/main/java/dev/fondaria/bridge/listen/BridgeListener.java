package dev.fondaria.bridge.listen;

import com.rabbitmq.client.Channel;
import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.cdc.CdcMessageException;
import dev.fondaria.bridge.cdc.CdcMessageParser;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.publish.DlqEnvelope;
import dev.fondaria.bridge.publish.LedgerEventPublisher;
import dev.fondaria.bridge.publish.LedgerTopics;
import dev.fondaria.bridge.publish.ProducerConfirmException;
import dev.fondaria.bridge.transform.LedgerEventTransform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bridge.ledger} — the bridge's only input — and drives one change event through
 * parse → explode → produce → acknowledge.
 *
 * <p><b>The acknowledgement is manual and comes last.</b> The AMQP message is acknowledged only
 * after {@link LedgerEventPublisher} confirms every produced record. So:
 *
 * <ul>
 *   <li><b>RabbitMQ confirms</b> → {@code basicAck}. Done.
 *   <li><b>The message will not parse</b> ({@link CdcMessageException}) → write it verbatim to
 *       {@code ledger.entries.dlq}, then {@code basicAck}. Redelivering the same bytes would fail
 *       forever; the bytes are preserved for a human and the CDC lag alert fires on the gap.
 *   <li><b>RabbitMQ does not confirm</b> ({@link ProducerConfirmException}) → pause briefly, then
 *       {@code basicNack(requeue=true)}. The message is <i>not</i> acknowledged, RabbitMQ
 *       redelivers it, and the write is retried. A crash before the ack has the same effect.
 * </ul>
 *
 * <p>The listener container runs {@code BRIDGE_AMQP_CONCURRENCY} competing consumers with a bounded
 * prefetch (see {@code application.yaml}). With 1 the CDC feed stays one ordered stream; with more,
 * events are republished out of order, which is safe because the projection does not depend on
 * order ({@code ADR-0024}). Measured on kind, 4 consumers republish about three times as fast as
 * one (row 26g). Nothing here is a database write, an id derivation or a rule — this is a
 * transport.
 */
@Component
public class BridgeListener {

  /**
   * Task 4.1's queue. Not configurable — it is topology, declared by the {@code rabbitmq-topology}
   * one-shot.
   */
  public static final String QUEUE = "bridge.ledger";

  private static final Logger LOGGER = LoggerFactory.getLogger(BridgeListener.class);

  private final CdcMessageParser parser;
  private final LedgerEventTransform transform;
  private final LedgerEventPublisher publisher;
  private final Clock clock;
  private final long retryBackoffMillis;

  public BridgeListener(
      CdcMessageParser parser,
      LedgerEventTransform transform,
      LedgerEventPublisher publisher,
      Clock clock,
      BridgeProperties properties) {
    this.parser = parser;
    this.transform = transform;
    this.publisher = publisher;
    this.clock = clock;
    this.retryBackoffMillis = properties.producerRetryBackoff().toMillis();
  }

  @RabbitListener(queues = QUEUE)
  public void onMessage(
      Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    byte[] body = message.getBody();
    try {
      CdcMessage event = parser.parse(body);
      publisher.publishAll(transform.explode(event));
      channel.basicAck(deliveryTag, false);
    } catch (CdcMessageException poison) {
      routeToDlq(message, body, poison, channel, deliveryTag);
    } catch (ProducerConfirmException transientFailure) {
      LOGGER.warn(
          "RabbitMQ did not confirm a published message; leaving it unacknowledged for"
              + " redelivery: {}",
          transientFailure.getMessage());
      backOff();
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void routeToDlq(
      Message message, byte[] body, CdcMessageException poison, Channel channel, long deliveryTag)
      throws IOException {
    LOGGER.error(
        "Unparseable bridge.ledger message routed to {}: {}",
        LedgerTopics.ENTRIES_DLQ,
        poison.getMessage());
    DlqEnvelope envelope =
        new DlqEnvelope(
            LedgerTopics.SCHEMA_VERSION,
            DlqEnvelope.REASON_UNPARSEABLE,
            poison.getMessage(),
            clock.instant().toString(),
            message.getMessageProperties().getReceivedExchange(),
            message.getMessageProperties().getReceivedRoutingKey(),
            new String(body, StandardCharsets.UTF_8));
    try {
      publisher.publishDlq(envelope);
      channel.basicAck(deliveryTag, false);
    } catch (ProducerConfirmException dlqFailed) {
      LOGGER.warn(
          "Could not write a poison message to {}; leaving it unacknowledged for redelivery: {}",
          LedgerTopics.ENTRIES_DLQ,
          dlqFailed.getMessage());
      backOff();
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void backOff() {
    if (retryBackoffMillis <= 0) {
      return;
    }
    try {
      TimeUnit.MILLISECONDS.sleep(retryBackoffMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
