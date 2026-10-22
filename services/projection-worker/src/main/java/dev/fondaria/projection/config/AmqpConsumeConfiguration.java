package dev.fondaria.projection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.ledger.LedgerEventDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * The projection consumer's AMQP wiring (ADR-0022).
 *
 * <p>Declarations:
 *
 * <ul>
 *   <li>Fanout exchange {@code ledger.transfers.v1} and queue {@code projection.transfers.v1}.
 *   <li>Consistent-hash exchange {@code ledger.entries.v1} and N queues {@code
 *       projection.entries.v1.0} through {@code .N-1} bound with weight "10".
 *   <li>Dead-letter queue {@code ledger.entries.dlq}.
 * </ul>
 *
 * <p>Two listener container factories, both {@link SimpleRabbitListenerContainerFactory} in batch
 * mode ({@code ADR-0034}): each consumer hands the listener up to {@code PROJECTION_BATCH_SIZE}
 * deliveries (default 200) or whatever arrived within {@code PROJECTION_BATCH_TIMEOUT} (default 100
 * ms), with {@code AcknowledgeMode.MANUAL}; the listener projects a batch in one transaction and
 * acknowledges it once. Spring's direct container, used until ADR-0034, has no batch mode.
 *
 * <ul>
 *   <li>{@code projectionTransfersContainerFactory}: {@code PROJECTION_TRANSFER_CONSUMERS}
 *       competing consumers (default 4) on the transfers queue. {@code projection.transfer} is
 *       insert-only, so these batches never wait on each other.
 *   <li>{@code projectionEntriesContainerFactory}: one consumer per container, used by {@link
 *       dev.fondaria.projection.consume.EntriesQueueListeners} to give each consistent-hash entries
 *       queue its own container with one <b>exclusive</b> consumer (ADR-0034 amendment, row 26w).
 *       The broker grants an exclusive consume to one consumer per queue across all connections, so
 *       in steady state exactly one consumer in the deployment drains a queue; every other pod's
 *       consumer is refused and retries on the recovery interval as a hot standby. Two batches
 *       therefore do not hold legs of one holding, and {@code ParticipantUnitsProjection}'s
 *       advisory lock ({@code ADR-0024}) is a safety net, contended only during a queue handover.
 * </ul>
 *
 * <p>Each consumer writes only its own checkpoint rows ({@code ADR-0032} §1).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  ProjectionConsumerProperties.class,
  CheckpointProperties.class,
  HaltProperties.class
})
public class AmqpConsumeConfiguration {

  @Bean
  public LedgerEventDecoder ledgerEventDecoder(ObjectMapper objectMapper) {
    return new LedgerEventDecoder(objectMapper);
  }

  @Bean
  public Declarables projectionTopology(ProjectionConsumerProperties properties) {
    properties.requireComplete();

    List<org.springframework.amqp.core.Declarable> objects = new ArrayList<>();

    // 1. Exchanges
    FanoutExchange transfersExchange =
        new FanoutExchange(properties.transfersExchange(), true, false);
    CustomExchange entriesExchange =
        new CustomExchange(
            properties.entriesExchange(), "x-consistent-hash", true, false, Map.of());
    objects.add(transfersExchange);
    objects.add(entriesExchange);

    // 2. Transfers queue and binding
    Queue transfersQueue = new Queue(properties.transfersQueue(), true);
    objects.add(transfersQueue);
    objects.add(BindingBuilder.bind(transfersQueue).to(transfersExchange));

    // 3. Entries consistent-hash queues and bindings (with weight "10")
    for (String queueName : properties.entriesQueueNames()) {
      Queue queue = new Queue(queueName, true);
      objects.add(queue);
      objects.add(
          new Binding(
              queueName,
              Binding.DestinationType.QUEUE,
              properties.entriesExchange(),
              "10",
              Map.of()));
    }

    // 4. DLQ queue
    Queue dlqQueue = new Queue(properties.deadLetterQueue(), true);
    objects.add(dlqQueue);

    return new Declarables(objects);
  }

  /** The transfers queue, consumed by {@code projectionTransfersContainerFactory}'s consumers. */
  @Bean(name = "projectionTransfersQueue")
  public String projectionTransfersQueue(ProjectionConsumerProperties properties) {
    return properties.transfersQueue();
  }

  @Bean
  public SimpleRabbitListenerContainerFactory projectionTransfersContainerFactory(
      ConnectionFactory connectionFactory,
      ProjectionConsumerProperties properties,
      CheckpointProperties checkpointProperties,
      DatabaseProperties databaseProperties) {
    SimpleRabbitListenerContainerFactory factory =
        batchFactory(connectionFactory, properties, checkpointProperties, databaseProperties);
    factory.setConcurrentConsumers(properties.transferConsumers());
    factory.setMaxConcurrentConsumers(properties.transferConsumers());
    return factory;
  }

  /**
   * One consumer per container; {@link dev.fondaria.projection.consume.EntriesQueueListeners} marks
   * each endpoint exclusive. A consumer the broker refuses because another pod holds the queue is
   * restarted after the recovery interval (spring-rabbit's default, 5 s); the refusal and the
   * restart are logged at debug by spring-rabbit, so a standby pod does not log every five seconds.
   */
  @Bean
  public SimpleRabbitListenerContainerFactory projectionEntriesContainerFactory(
      ConnectionFactory connectionFactory,
      ProjectionConsumerProperties properties,
      CheckpointProperties checkpointProperties,
      DatabaseProperties databaseProperties) {
    SimpleRabbitListenerContainerFactory factory =
        batchFactory(connectionFactory, properties, checkpointProperties, databaseProperties);
    factory.setConcurrentConsumers(1);
    factory.setMaxConcurrentConsumers(1);
    return factory;
  }

  private static SimpleRabbitListenerContainerFactory batchFactory(
      ConnectionFactory connectionFactory,
      ProjectionConsumerProperties properties,
      CheckpointProperties checkpointProperties,
      DatabaseProperties databaseProperties) {
    properties.requireComplete();
    properties.requireConsumersFitPool(databaseProperties.maxPoolSize());
    checkpointProperties.requireActiveWindowExceeds(properties.retryMaxBackoff());

    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    // Not auto-started: ClusterHalt starts the containers once it has read the halt flag and
    // found none, so a pod scheduled during a halt never consumes (ADR-0032 §2).
    factory.setAutoStartup(false);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    // ADR-0034 §1: the listener receives List<Message> from one consumer's channel and
    // acknowledges it with one multiple-ack after one commit.
    factory.setConsumerBatchEnabled(true);
    factory.setBatchListener(true);
    factory.setBatchSize(properties.batchSize());
    factory.setBatchReceiveTimeout(properties.batchTimeout().toMillis());
    // A consumer cannot fill a batch larger than what the broker lets it hold unacknowledged.
    // Global QoS makes the limit per channel, so one batch stuck on a persistent failure pins one
    // batch's worth of messages, not this many per queue.
    factory.setPrefetchCount(Math.max(properties.prefetch(), properties.batchSize()));
    factory.setGlobalQos(true);
    factory.setTaskExecutor(consumerExecutor());
    return factory;
  }

  /**
   * Consumer threads on virtual threads, like the rest of the service (docs/ENGINEERING-RULES.md
   * §4).
   */
  private static SimpleAsyncTaskExecutor consumerExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("projection-consumer-");
    executor.setVirtualThreads(true);
    return executor;
  }
}
