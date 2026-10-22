package dev.fondaria.projection.consume;

import com.rabbitmq.client.Channel;
import dev.fondaria.ledger.LedgerEventDecoder;
import dev.fondaria.ledger.MalformedLedgerEventException;
import dev.fondaria.ledger.UnknownLedgerEventVersionException;
import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.projection.config.ProjectionConsumerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The projection's one input: {@code ledger.transfers.v1} and {@code ledger.entries.v1} from
 * RabbitMQ (ADR-0022).
 *
 * <p><b>Per-account ordering is a throughput characteristic here, not a correctness dependency.</b>
 * The entries exchange is an {@code x-consistent-hash} exchange routing by {@code account_id} into
 * N queues, each drained in steady state by exactly one exclusive consumer across every replica
 * ({@link EntriesQueueListeners}, ADR-0034 amendment) — so two events for the same account are not
 * normally in flight at once (a queue handover is the brief exception). Nothing below <i>relies</i>
 * on that: {@link ParticipantUnitsProjection} (Task F.3, {@code ADR-0024}) converges correctly,
 * including its hash chain, regardless of the order entries for one account arrive in, and takes
 * its own per-{@code (tenant, participant, fund)} lock rather than assuming the queue topology
 * serializes it. A misconfigured exchange that degraded to round-robin would no longer be a
 * money-correctness incident, only a lock-contention one.
 *
 * <p><b>Batches (ADR-0034).</b> The container hands this listener up to {@code
 * PROJECTION_BATCH_SIZE} deliveries from one consumer's channel at a time. The batch is decoded
 * first, projected by the sink in one transaction, and acknowledged with one {@code basicAck(last,
 * multiple)}: delivery tags on a channel increase and every earlier batch is already acknowledged,
 * so that acks exactly this batch.
 *
 * <p><b>Acknowledgement is manual and last.</b> A batch is acknowledged only after the sink has
 * committed it. A crash, a clean shutdown, or a transient failure before that leaves the whole
 * batch unacknowledged and it is redelivered. The sink is idempotent on {@code transfer_id} (Task
 * 5.4), so redelivery is safe.
 *
 * <ul>
 *   <li><b>Decodes cleanly, sink returns</b> → one {@code basicAck} for the batch.
 *   <li><b>Will not decode</b> ({@link UnknownLedgerEventVersionException} / {@link
 *       MalformedLedgerEventException}) → the deliveries before it are projected and acknowledged
 *       as a batch of their own, those after it are left unacknowledged, and for the poison one:
 *       count it on {@code projection_poison_total}, trip {@link ProjectionHalt}, raise the
 *       cluster-wide halt flag ({@link ClusterHalt}, so every replica stops, not just this one),
 *       preserve the bytes to {@code ledger.entries.dlq}, and stop every listener container. The
 *       delivery is never acknowledged: the gap is closed by a deliberate restart, never skipped
 *       (docs/ENGINEERING-RULES.md §4, ADR-0032 §2).
 *   <li><b>Halted</b> (this pod's latch is tripped, by its own poison or by another replica's) →
 *       nothing further is dispatched and nothing is acknowledged, before the sink and again
 *       between the sink's commit and the {@code basicAck}. A delivery that committed but was not
 *       acknowledged is redelivered later; the idempotent sink absorbs it.
 *   <li><b>Transient failure</b> (the sink throws anything else) → the batch's transaction rolls
 *       back and the whole batch retries with bounded exponential backoff within the consumer
 *       thread, preserving queue ordering.
 * </ul>
 */
@Component
public class LedgerEventListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(LedgerEventListener.class);

  /** phase-11 Task 11.2 reads this. Incremented once per poison record encountered. */
  static final String POISON_COUNTER = "projection_poison_total";

  private final LedgerEventDecoder decoder;
  private final ProjectionSink sink;
  private final DeadLetterPublisher deadLetters;
  private final ProjectionHalt halt;
  private final ListenerContainers containers;
  private final ClusterHalt clusterHalt;
  private final String transfersExchange;
  private final String entriesExchange;
  private final String transfersQueue;
  private final String entriesQueuePrefix;
  private final Duration retryInitialBackoff;
  private final Duration retryMaxBackoff;
  private final Counter poisonCounter;
  private final CdcLagMetrics lag;
  private final String instanceId;

  public LedgerEventListener(
      LedgerEventDecoder decoder,
      ProjectionSink sink,
      DeadLetterPublisher deadLetters,
      ProjectionHalt halt,
      ListenerContainers containers,
      ClusterHalt clusterHalt,
      ProjectionConsumerProperties properties,
      MeterRegistry meterRegistry,
      CdcLagMetrics lag,
      CheckpointProperties checkpointProperties) {
    this.decoder = decoder;
    this.sink = sink;
    this.deadLetters = deadLetters;
    this.halt = halt;
    this.containers = containers;
    this.clusterHalt = clusterHalt;
    this.transfersExchange = properties.transfersExchange();
    this.entriesExchange = properties.entriesExchange();
    this.transfersQueue = properties.transfersQueue();
    this.entriesQueuePrefix = properties.entriesQueuePrefix();
    this.retryInitialBackoff = properties.retryInitialBackoff();
    this.retryMaxBackoff = properties.retryMaxBackoff();
    this.poisonCounter =
        Counter.builder(POISON_COUNTER)
            .description(
                "CDC messages the projection could not decode and dead-lettered before halting")
            .register(meterRegistry);
    this.lag = lag;
    this.instanceId = checkpointProperties.instanceId();
  }

  /**
   * One consumer's batch. The transfers queue's competing consumers arrive here through the
   * annotation; each entries queue's single exclusive consumer arrives through {@link
   * EntriesQueueListeners} (ADR-0034 amendment, row 26w). The handling is the same for both.
   */
  @RabbitListener(
      queues = "#{projectionTransfersQueue}",
      containerFactory = "projectionTransfersContainerFactory")
  public void onBatch(List<Message> messages, Channel channel) throws IOException {
    // Halted: leave the deliveries unacknowledged. They return to their queues when this pod's
    // channel closes, and are redelivered to a pod started after the operator has cleared the
    // halt.
    if (messages.isEmpty() || halt.isHalted()) {
      return;
    }

    // ADR-0034 §4: find the first delivery that will not decode before anything is projected, so
    // nothing after a poison message is ever applied ahead of it.
    int poisonAt = messages.size();
    RuntimeException poison = null;
    for (int i = 0; i < messages.size(); i++) {
      try {
        decode(messages.get(i));
      } catch (UnknownLedgerEventVersionException | MalformedLedgerEventException e) {
        poisonAt = i;
        poison = e;
        break;
      } catch (RuntimeException routingFailure) {
        // Not a poison message: dispatchWithRetry meets the same failure and retries it.
      }
    }

    List<Message> clean = messages.subList(0, poisonAt);
    if (!clean.isEmpty()) {
      long lastTag = clean.get(clean.size() - 1).getMessageProperties().getDeliveryTag();
      boolean committed;
      try {
        committed = dispatchWithRetry(clean);
      } catch (LatePoison late) {
        // Decoded cleanly a moment ago, poison now: halt on that delivery exactly as on any
        // other. Halting first means nothing on this channel is acknowledged again, so no later
        // multiple-ack can cover this batch; it returns to its queues when the channel closes.
        haltOn(late.message, reasonOf(late.poison), late.poison);
        throw late.poison;
      } catch (RuntimeException interrupted) {
        requeueUnlessHalted(channel, messages);
        throw interrupted;
      }
      if (!committed || halt.isHalted()) {
        requeueUnlessHalted(channel, messages);
        return;
      }
      channel.basicAck(lastTag, true);
    }

    if (poison != null) {
      haltOn(messages.get(poisonAt), reasonOf(poison), poison);
      throw poison;
    }
  }

  /**
   * Review of row 26u: a multiple-ack covers every unacknowledged delivery on the channel up to its
   * tag. A batch this listener returns from without acknowledging — an interrupt, a retry abandoned
   * — must therefore not stay unacknowledged on a channel that keeps consuming, or the next batch's
   * ack would cover it unprojected. It goes back to its queues instead. A halted pod is the
   * exception: it acknowledges nothing again, and its unacknowledged deliveries return when the
   * channel closes, so they are left exactly as today.
   */
  private void requeueUnlessHalted(Channel channel, List<Message> batch) throws IOException {
    if (halt.isHalted()) {
      return;
    }
    long lastTag = batch.get(batch.size() - 1).getMessageProperties().getDeliveryTag();
    channel.basicNack(lastTag, true, true);
  }

  /**
   * A delivery the pre-scan decoded but the retry loop could not: carries which one, so the halt
   * dead-letters the right bytes. Unreachable with a deterministic decoder; guarded anyway.
   */
  private static final class LatePoison extends RuntimeException {
    private final transient Message message;
    private final RuntimeException poison;

    LatePoison(Message message, RuntimeException poison) {
      super(poison.getMessage(), poison, false, false);
      this.message = message;
      this.poison = poison;
    }
  }

  private static String reasonOf(RuntimeException poison) {
    return poison instanceof UnknownLedgerEventVersionException
        ? DeadLetterPublisher.REASON_UNKNOWN_VERSION
        : DeadLetterPublisher.REASON_MALFORMED;
  }

  /**
   * @return {@code true} once the sink has committed the batch; {@code false} if the loop gave up
   *     without processing it (an interrupt, or this pod halting mid-retry) — the caller must then
   *     not acknowledge it.
   */
  private boolean dispatchWithRetry(List<Message> batch) {
    Duration backoff = retryInitialBackoff;
    while (!Thread.currentThread().isInterrupted() && !halt.isHalted()) {
      try {
        List<ProjectionSink.Delivery> deliveries = new ArrayList<>(batch.size());
        for (Message message : batch) {
          ProjectionSink.Delivery delivery;
          try {
            delivery = decode(message);
          } catch (UnknownLedgerEventVersionException | MalformedLedgerEventException poison) {
            throw new LatePoison(message, poison);
          }
          lag.headReceivedAt(delivery.ledgerTimestamp());
          deliveries.add(delivery);
        }
        sink.acceptBatch(deliveries);
        return true;
      } catch (LatePoison late) {
        throw late;
      } catch (Exception transientEx) {
        LOGGER.warn(
            "transient failure projecting a batch of {} from queue {}: {} - retrying in {}",
            batch.size(),
            batch.get(0).getMessageProperties().getConsumerQueue(),
            transientEx.getMessage(),
            backoff);
        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("interrupted while retrying transient failure", ie);
        }
        long next = Math.min(backoff.toMillis() * 2, retryMaxBackoff.toMillis());
        backoff = Duration.ofMillis(next);
      }
    }
    return false;
  }

  private ProjectionSink.Delivery decode(Message message) {
    String queue = message.getMessageProperties().getConsumerQueue();
    String exchange = message.getMessageProperties().getReceivedExchange();
    byte[] body = message.getBody();

    if (transfersQueue.equals(queue) || transfersExchange.equals(exchange)) {
      return new ProjectionSink.Delivery.Transfer(
          decoder.decodeTransfer(body), consumerOf(message));
    }
    if ((queue != null && queue.startsWith(entriesQueuePrefix))
        || entriesExchange.equals(exchange)) {
      return new ProjectionSink.Delivery.Entry(decoder.decodeEntry(body), consumerOf(message));
    }
    throw new IllegalStateException(
        "projection received message from unexpected queue/exchange: queue="
            + queue
            + ", exchange="
            + exchange);
  }

  /**
   * The checkpoint row this delivery advances: {@code <instance>/<consumer tag>} (ADR-0032 §1). The
   * listener container stamps the consumer tag and queue on every delivery; one consumer's
   * deliveries run on one thread at a time, so the row has exactly one writer.
   */
  private ProjectionCheckpoint.ConsumerRef consumerOf(Message message) {
    var properties = message.getMessageProperties();
    String queue = properties.getConsumerQueue();
    return ProjectionCheckpoint.ConsumerRef.of(
        instanceId, properties.getConsumerTag(), queue == null ? "" : queue);
  }

  private void haltOn(Message message, String reason, RuntimeException cause) {
    poisonCounter.increment();
    String queue = message.getMessageProperties().getConsumerQueue();
    // Order (ADR-0032 §2): the local latch first, so no thread in this JVM acks or dispatches from
    // here on; then the shared flag, because it is what stops the other replicas and the DLQ
    // publish below can take up to its confirm timeout; then the bytes; then the containers.
    halt.trigger(
        "poison message at queue %s exchange %s (%s): %s"
            .formatted(
                queue,
                message.getMessageProperties().getReceivedExchange(),
                reason,
                cause.getMessage()));
    clusterHalt.raise(reason, queue == null ? "" : queue);
    boolean preserved = deadLetters.publish(message, reason, cause.getMessage());
    if (!preserved) {
      LOGGER.error("poison message NOT preserved to the dead-letter queue; restart retries it");
    }
    containers.stopAll();
    LOGGER.error("projection halted on a poison message; readiness will report DOWN");
  }
}
