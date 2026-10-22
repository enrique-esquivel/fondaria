package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.ProjectionConsumerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * One listener container per consistent-hash entries queue, each with one <b>exclusive</b> consumer
 * (ADR-0034 amendment, row 26w).
 *
 * <p>RabbitMQ grants an exclusive consume to one consumer per queue across every connection. So in
 * steady state exactly one consumer in the whole deployment drains a given entries queue, and since
 * the entries exchange routes every leg of one account to one queue, two batches do not hold legs
 * of one holding. Row 26u's first build had every consumer read every queue; its load test (#174)
 * showed the two legs of one vested holding landing in two concurrent batches, the second waiting
 * on the first's advisory lock until that whole batch committed.
 *
 * <p>Every other replica registers the same containers. Their consumers are refused by the broker
 * and restarted by spring-rabbit after its recovery interval, so each one is a hot standby that
 * takes its queue over once the owner's consume is cancelled (graceful stop, halt) or its
 * connection drops (crash). A graceful stop cancels before the owner's in-flight batch commits, so
 * for a few seconds two consumers can hold legs of one holding; {@link
 * ParticipantUnitsProjection}'s advisory lock and timestamp-guarded upsert ({@code ADR-0024}) make
 * that correct. Nothing is redeclared: {@code exclusive} is a flag on the consume, not a queue
 * argument.
 *
 * <p>A batch that keeps failing (not a decode failure, which halts) is retried by its owner for
 * ever, and the owner keeps the queue meanwhile: that shard stops until the cause is fixed, and
 * nothing is skipped (ADR-0034 amendment, item 5).
 *
 * <p>The containers are registered with the same endpoint registry as the transfers listener, so
 * {@link ListenerContainers} starts and stops them with it ({@link ClusterHalt}, ADR-0032 §2), and
 * each batch is handled by {@link LedgerEventListener#onBatch} exactly as a transfers batch is.
 */
@Component
public class EntriesQueueListeners implements RabbitListenerConfigurer {

  static final String ENDPOINT_ID_PREFIX = "projection-entries-";

  private final LedgerEventListener listener;
  private final List<String> queues;
  private final SimpleRabbitListenerContainerFactory factory;

  public EntriesQueueListeners(
      LedgerEventListener listener,
      ProjectionConsumerProperties properties,
      @Qualifier("projectionEntriesContainerFactory")
          SimpleRabbitListenerContainerFactory factory) {
    this.listener = listener;
    this.queues = properties.entriesQueueNames();
    this.factory = factory;
  }

  @Override
  public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
    ChannelAwareBatchMessageListener batchListener =
        (messages, channel) -> {
          try {
            listener.onBatch(messages, channel);
          } catch (IOException e) {
            // An ack or nack that failed on the channel: the container treats it like any
            // listener failure, and the channel's unacknowledged deliveries are redelivered.
            throw new UncheckedIOException(e);
          }
        };
    for (String queue : queues) {
      SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
      endpoint.setId(ENDPOINT_ID_PREFIX + queue);
      endpoint.setQueueNames(queue);
      endpoint.setExclusive(true);
      endpoint.setConcurrency("1");
      endpoint.setBatchListener(true);
      endpoint.setMessageListener(batchListener);
      registrar.registerEndpoint(endpoint, factory);
    }
  }
}
