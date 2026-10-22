package dev.fondaria.projection.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The projection consumer's own configuration, bound from the environment under {@code
 * fondaria.projection}.
 *
 * <p>The RabbitMQ <em>connection</em> is bound by Spring under {@code spring.rabbitmq.*}; this
 * record mirrors the one value that must be present ({@code amqpHost}) so an absent variable is a
 * named {@link ConfigurationException} rather than a consumer that silently connects to {@code
 * localhost} and never sees an event. Everything else is operational tuning with a working default
 * (docs/ENGINEERING-RULES.md §4: production fails to start on an absent secret; tuning is not a
 * secret).
 *
 * @param amqpHost {@code PROJECTION_AMQP_HOST} — the broker the projection reads its input
 *     exchanges from and writes the poison record to. Required.
 * @param transfersExchange the fanout exchange carrying one message per change event, keyed by
 *     {@code transfer_id}. Defaults to {@code ledger.transfers.v1}.
 * @param entriesExchange the consistent-hash exchange carrying two messages per change event, one
 *     per leg, keyed by {@code account_id}. Defaults to {@code ledger.entries.v1}.
 * @param deadLetterQueue where a message this consumer cannot decode is preserved before the halt.
 *     Defaults to {@code ledger.entries.dlq}.
 * @param transfersQueue the queue bound to {@code transfersExchange}. Defaults to {@code
 *     projection.transfers.v1}.
 * @param entriesQueuePrefix prefix for the consistent-hash queues bound to {@code entriesExchange}.
 *     Defaults to {@code projection.entries.v1}.
 * @param entriesQueueCount how many consistent-hash queues are declared and consumed. Defaults to
 *     16.
 * @param prefetch RabbitMQ prefetch count per consumer channel. Defaults to 200.
 * @param transferConsumers {@code PROJECTION_TRANSFER_CONSUMERS} — competing batch consumers this
 *     process runs on the transfers queue (ADR-0034, amended by row 26w). The transfers queue is
 *     insert-only, so its batches never wait on each other. The entries queues are not counted
 *     here: each has exactly one exclusive consumer per process, of which only one in the whole
 *     deployment is active. Each active consumer holds one pool connection for its batch's
 *     transaction; see {@link #requireConsumersFitPool}. Defaults to 4.
 * @param batchSize {@code PROJECTION_BATCH_SIZE} — the most deliveries one batch, and so one
 *     transaction and one acknowledgement, carries (ADR-0034 §1). Defaults to 200.
 * @param batchTimeout {@code PROJECTION_BATCH_TIMEOUT} — how long a consumer waits to fill a batch
 *     before projecting what it has, which bounds the latency batching adds. Defaults to 100 ms.
 * @param retryInitialBackoff first pause after a transient failure before retrying.
 * @param retryMaxBackoff the cap the exponential backoff grows to. Retries never stop — a gap in a
 *     financial projection is worse than downtime (docs/ENGINEERING-RULES.md §4) — so the interval
 *     is bounded, not the attempt count.
 * @param deadLetterTimeout how long to wait for RabbitMQ to confirm the one poison record written
 *     to {@code ledger.entries.dlq} before giving up and halting anyway.
 */
@ConfigurationProperties(prefix = "fondaria.projection")
public record ProjectionConsumerProperties(
    String amqpHost,
    String transfersExchange,
    String entriesExchange,
    String deadLetterQueue,
    String transfersQueue,
    String entriesQueuePrefix,
    int entriesQueueCount,
    int prefetch,
    int transferConsumers,
    int batchSize,
    Duration batchTimeout,
    Duration retryInitialBackoff,
    Duration retryMaxBackoff,
    Duration deadLetterTimeout) {

  public static final String AMQP_HOST_VARIABLE = "PROJECTION_AMQP_HOST";

  /** ADR-0034 (row 26w): competing batch consumers per pod on the transfers queue. */
  public static final int DEFAULT_TRANSFER_CONSUMERS = 4;

  /** ADR-0034 §1: deliveries per batch, so per transaction and per acknowledgement. */
  public static final int DEFAULT_BATCH_SIZE = 200;

  /**
   * Connections a pod keeps free of consumers: the cluster-halt poller and the health and metrics
   * queries also borrow from the pool (ADR-0032 §2).
   */
  static final int POOL_HEADROOM = 2;

  static final String REMEDY =
      "Set "
          + AMQP_HOST_VARIABLE
          + " (the RabbitMQ host) in the environment. See"
          + " infrastructure/compose/.env.example.";

  public ProjectionConsumerProperties {
    transfersExchange = blankToDefault(transfersExchange, "ledger.transfers.v1");
    entriesExchange = blankToDefault(entriesExchange, "ledger.entries.v1");
    deadLetterQueue = blankToDefault(deadLetterQueue, "ledger.entries.dlq");
    transfersQueue = blankToDefault(transfersQueue, "projection.transfers.v1");
    entriesQueuePrefix = blankToDefault(entriesQueuePrefix, "projection.entries.v1");
    entriesQueueCount = entriesQueueCount <= 0 ? 16 : entriesQueueCount;
    prefetch = prefetch <= 0 ? 200 : prefetch;
    transferConsumers = transferConsumers <= 0 ? DEFAULT_TRANSFER_CONSUMERS : transferConsumers;
    batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    batchTimeout =
        batchTimeout == null || batchTimeout.isNegative() || batchTimeout.isZero()
            ? Duration.ofMillis(100)
            : batchTimeout;
    retryInitialBackoff = retryInitialBackoff == null ? Duration.ofSeconds(1) : retryInitialBackoff;
    retryMaxBackoff = retryMaxBackoff == null ? Duration.ofSeconds(30) : retryMaxBackoff;
    deadLetterTimeout = deadLetterTimeout == null ? Duration.ofSeconds(10) : deadLetterTimeout;
  }

  /**
   * Fails startup naming the absent variable. Never echoes a value (docs/ENGINEERING-RULES.md §7).
   */
  public void requireComplete() {
    if (amqpHost == null || amqpHost.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + AMQP_HOST_VARIABLE + " is not set", REMEDY);
    }
  }

  /**
   * ADR-0034 (row 26w): every active consumer holds a pool connection for its whole batch. One pod
   * may own every entries queue (the first to start usually does), so the worst case is one
   * consumer per entries queue plus the transfer consumers, and that must leave the pool {@link
   * #POOL_HEADROOM} connections for the halt poller and health checks. Fails startup naming the
   * variables rather than letting consumers starve the halt check of a connection.
   */
  public void requireConsumersFitPool(int maxPoolSize) {
    int worstCase = entriesQueueCount + transferConsumers;
    if (worstCase > maxPoolSize - POOL_HEADROOM) {
      throw new ConfigurationException(
          "PROJECTION_ENTRIES_QUEUE_COUNT ("
              + entriesQueueCount
              + ") plus PROJECTION_TRANSFER_CONSUMERS ("
              + transferConsumers
              + ") must be at most POSTGRES_MAX_POOL_SIZE ("
              + maxPoolSize
              + ") minus "
              + POOL_HEADROOM,
          "Lower PROJECTION_TRANSFER_CONSUMERS or raise POSTGRES_MAX_POOL_SIZE.");
    }
  }

  /**
   * The consistent-hash entries queues, {@code <prefix>.0} through {@code <prefix>.<count - 1>}:
   * the names declared and bound by {@code AmqpConsumeConfiguration}, each consumed by its own
   * exclusive container (ADR-0034 amendment, row 26w).
   */
  public List<String> entriesQueueNames() {
    List<String> names = new ArrayList<>(entriesQueueCount);
    for (int i = 0; i < entriesQueueCount; i++) {
      names.add(entriesQueuePrefix + "." + i);
    }
    return List.copyOf(names);
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
