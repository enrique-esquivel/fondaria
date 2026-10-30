package dev.fondaria.bridge.listen;

import com.rabbitmq.client.Channel;
import dev.fondaria.bridge.cdc.CdcMessageException;
import dev.fondaria.bridge.cdc.CdcMessageParser;
import dev.fondaria.bridge.config.BridgeListenerConfiguration;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.publish.DlqEnvelope;
import dev.fondaria.bridge.publish.LedgerEventPublisher;
import dev.fondaria.bridge.publish.LedgerTopics;
import dev.fondaria.bridge.publish.ProducerConfirmException;
import dev.fondaria.bridge.publish.RabbitMetrics;
import dev.fondaria.bridge.transform.LedgerEventTransform;
import dev.fondaria.bridge.transform.LedgerRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bridge.ledger} — the bridge's only input — and drives each batch of change events
 * through parse → explode → produce → acknowledge.
 *
 * <p><b>The acknowledgement is manual, comes last, and covers the batch.</b> A consumer receives up
 * to {@code BRIDGE_AMQP_BATCH_SIZE} messages at once ({@link BridgeListenerConfiguration}). Every
 * record of every event in the batch is published, and only after {@link LedgerEventPublisher}
 * confirms all of them is the batch acknowledged, with one {@code basicAck(lastTag, multiple)}:
 *
 * <ul>
 *   <li><b>RabbitMQ confirms everything</b> → one multiple-ack for the batch. Done.
 *   <li><b>A message will not parse</b> ({@link CdcMessageException}) → its bytes go verbatim to
 *       {@code ledger.entries.dlq} in the same publish, and it is acknowledged with the rest.
 *       Redelivering the same bytes would fail forever; they are preserved for a human and the CDC
 *       lag alert fires on the gap. One poison message never holds back the events around it.
 *   <li><b>RabbitMQ does not confirm something</b> ({@link ProducerConfirmException}) → the
 *       consumer <b>halts on the batch</b>: it keeps it unacknowledged and publishes it again, with
 *       a backoff doubling from {@code BRIDGE_PRODUCER_RETRY_BACKOFF} up to {@value
 *       #MAX_RETRY_BACKOFF_MILLIS} ms, until RabbitMQ confirms all of it. Its prefetch stays full,
 *       so it consumes nothing else meanwhile. Records already confirmed are published again; every
 *       consumer is idempotent on {@code transfer_id}.
 * </ul>
 *
 * <p><b>Why halt rather than nack</b> (ADR-0041). A nack with requeue spends one of {@code
 * bridge.ledger}'s five deliveries ({@code x-delivery-limit}), so a broker that stayed unable to
 * confirm for five backoffs dead-lettered every event: 64,832 on load run 8. A publish failure is a
 * fault of the path out, not of the message, and never-skip (docs/ENGINEERING-RULES.md §4) says
 * halt. The retry ends only when the batch is confirmed, or when the consumer's own channel has
 * closed — the broker has then already taken the batch back, exactly as after a crash — or the
 * thread is interrupted for shutdown. {@code bridge_publish_halted} counts consumers holding a
 * batch they cannot publish.
 *
 * <p>Why a batch. Per event the broker used to handle one consume, one ack and three publishes, and
 * each consumer waited a full confirm round trip (~10 ms) per event: on the 300k-participant load
 * run four consumers drained ~470 events/s with RabbitMQ pinned at its CPU limit. A batch replaces
 * N acks with one and N confirm waits with one, so each consumer keeps a whole batch of publishes
 * in flight.
 *
 * <p>Multiple-ack is exact because each consumer has its own channel and acknowledges a batch
 * before it receives the next: every delivery tag up to the batch's last is a message of this or an
 * earlier, already-acknowledged batch on that channel.
 *
 * <p>The container runs {@code BRIDGE_AMQP_CONCURRENCY} competing consumers; events are republished
 * out of order, which is safe because the projection does not depend on order ({@code ADR-0024}).
 * Nothing here is a database write, an id derivation or a rule — this is a transport.
 */
@Component
public class BridgeListener {

  /**
   * Task 4.1's queue. Not configurable — it is topology, declared by the {@code rabbitmq-topology}
   * one-shot.
   */
  public static final String QUEUE = "bridge.ledger";

  /** The longest pause between attempts to publish a held batch. */
  static final long MAX_RETRY_BACKOFF_MILLIS = 30_000L;

  private static final Logger LOGGER = LoggerFactory.getLogger(BridgeListener.class);

  private final CdcMessageParser parser;
  private final LedgerEventTransform transform;
  private final LedgerEventPublisher publisher;
  private final Clock clock;
  private final RabbitMetrics metrics;
  private final long retryBackoffMillis;

  public BridgeListener(
      CdcMessageParser parser,
      LedgerEventTransform transform,
      LedgerEventPublisher publisher,
      Clock clock,
      RabbitMetrics metrics,
      BridgeProperties properties) {
    this.parser = parser;
    this.transform = transform;
    this.publisher = publisher;
    this.clock = clock;
    this.metrics = metrics;
    this.retryBackoffMillis = properties.producerRetryBackoff().toMillis();
  }

  @RabbitListener(queues = QUEUE, containerFactory = BridgeListenerConfiguration.CONTAINER_FACTORY)
  public void onBatch(List<Message> batch, Channel channel) throws IOException {
    if (batch.isEmpty()) {
      return;
    }
    List<LedgerRecord> records = new ArrayList<>(batch.size() * 3);
    List<DlqEnvelope> deadLetters = new ArrayList<>(0);
    long lastTag = 0L;
    for (Message message : batch) {
      lastTag = Math.max(lastTag, message.getMessageProperties().getDeliveryTag());
      byte[] body = message.getBody();
      try {
        records.addAll(transform.explode(parser.parse(body)));
      } catch (CdcMessageException poison) {
        LOGGER.error(
            "Unparseable bridge.ledger message routed to {}: {}",
            LedgerTopics.ENTRIES_DLQ,
            poison.getMessage());
        deadLetters.add(envelope(message, body, poison));
      }
    }
    publishUntilConfirmed(records, deadLetters, batch.size(), channel);
    channel.basicAck(lastTag, true);
  }

  private void publishUntilConfirmed(
      List<LedgerRecord> records, List<DlqEnvelope> deadLetters, int batchSize, Channel channel) {
    long backoff = retryBackoffMillis;
    boolean halted = false;
    try {
      for (int attempt = 1; ; attempt++) {
        try {
          publisher.publishBatch(records, deadLetters);
          if (halted) {
            LOGGER.info(
                "RabbitMQ confirmed a held batch of {} after {} attempts; resuming",
                batchSize,
                attempt);
          }
          return;
        } catch (ProducerConfirmException transientFailure) {
          if (!halted) {
            halted = true;
            metrics.publishHalted();
          }
          if (!channel.isOpen()) {
            // RabbitMQ took the batch back when the channel closed; acknowledging is impossible.
            LOGGER.warn(
                "Consumer channel closed while holding an unconfirmed batch of {}; RabbitMQ"
                    + " redelivers it",
                batchSize);
            throw transientFailure;
          }
          LOGGER.warn(
              "RabbitMQ did not confirm a published message; holding a batch of {} unacknowledged"
                  + " and publishing it again in {} ms (attempt {}): {}",
              batchSize,
              backoff,
              attempt,
              transientFailure.getMessage());
          if (!sleep(backoff)) {
            throw transientFailure;
          }
          backoff = Math.min(MAX_RETRY_BACKOFF_MILLIS, Math.max(1L, backoff * 2));
        }
      }
    } finally {
      if (halted) {
        metrics.publishResumed();
      }
    }
  }

  private DlqEnvelope envelope(Message message, byte[] body, CdcMessageException poison) {
    return new DlqEnvelope(
        LedgerTopics.SCHEMA_VERSION,
        DlqEnvelope.REASON_UNPARSEABLE,
        poison.getMessage(),
        clock.instant().toString(),
        message.getMessageProperties().getReceivedExchange(),
        message.getMessageProperties().getReceivedRoutingKey(),
        new String(body, StandardCharsets.UTF_8));
  }

  /** False if interrupted: the container is stopping, and the batch goes back with the channel. */
  private static boolean sleep(long millis) {
    if (millis <= 0) {
      return true;
    }
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
