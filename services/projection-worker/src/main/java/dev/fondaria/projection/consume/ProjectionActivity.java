package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.ActivityProperties;
import dev.fondaria.projection.config.ProjectionConsumerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStoppedEvent;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerTerminatedEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * What this replica is consuming, in its own metrics and logs (ADR-0035). Before this, a pod
 * draining a 200,000-message backlog logged one line at startup and nothing else.
 *
 * <ul>
 *   <li>{@code projection_batches_total{queue}} and {@code projection_messages_total{queue}} —
 *       batches committed and acknowledged, and the messages in them. Counted after the ack, so a
 *       batch that is retried or redelivered counts once per commit.
 *   <li>{@code projection_queue_owned{queue}} — 1 while this pod holds the exclusive consume on an
 *       entries queue (ADR-0034 amendment), else 0. Across pods, {@code sum by (queue)} is 1 in
 *       steady state and 0 while nobody drains that queue.
 *   <li>One INFO line when an entries queue's exclusive consume is acquired or lost. A standby's
 *       refusals every recovery interval change nothing and log nothing.
 *   <li>One INFO summary every {@code PROJECTION_ACTIVITY_LOG_INTERVAL}: what was committed since
 *       the last one, per queue; owned entries queues; this pod's CDC lag; each queue's ready depth
 *       as the broker reports it; and the queues this pod consumes from that hold messages it did
 *       not drain at all.
 * </ul>
 *
 * <p>The {@code queue} label takes only the configured queue names, a fixed set. Nothing logged
 * here names a transfer, an account or an amount (docs/ENGINEERING-RULES.md §7).
 */
@Component
public class ProjectionActivity implements SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionActivity.class);

  static final String BATCHES_COUNTER = "projection_batches_total";
  static final String MESSAGES_COUNTER = "projection_messages_total";
  static final String OWNED_GAUGE = "projection_queue_owned";

  private final String transfersQueue;
  private final List<String> entriesQueues;
  private final List<String> allQueues;
  private final Map<String, QueueCounts> counts = new ConcurrentHashMap<>();
  private final Map<String, AtomicBoolean> owned = new LinkedHashMap<>();
  private final MeterRegistry registry;
  private final CdcLagMetrics lag;
  private final ProjectionHalt halt;
  private final QueueDepths depths;
  private final TaskScheduler scheduler;
  private final Clock clock;
  private final Duration logInterval;

  private volatile Instant windowStart;
  private volatile ScheduledFuture<?> summaries;

  public ProjectionActivity(
      MeterRegistry registry,
      ProjectionConsumerProperties consumerProperties,
      ActivityProperties activityProperties,
      CdcLagMetrics lag,
      ProjectionHalt halt,
      QueueDepths depths,
      TaskScheduler scheduler,
      Clock clock) {
    this.registry = registry;
    this.transfersQueue = consumerProperties.transfersQueue();
    this.entriesQueues = consumerProperties.entriesQueueNames();
    List<String> queues = new ArrayList<>();
    queues.add(transfersQueue);
    queues.addAll(entriesQueues);
    this.allQueues = List.copyOf(queues);
    this.lag = lag;
    this.halt = halt;
    this.depths = depths;
    this.scheduler = scheduler;
    this.clock = clock;
    this.logInterval = activityProperties.logInterval();
    this.windowStart = clock.instant();
    for (String queue : allQueues) {
      countsFor(queue);
    }
    for (String queue : entriesQueues) {
      AtomicBoolean flag = new AtomicBoolean();
      owned.put(queue, flag);
      Gauge.builder(OWNED_GAUGE, flag, f -> f.get() ? 1.0 : 0.0)
          .tag("queue", queue)
          .description("1 while this replica holds the exclusive consume on this entries queue")
          .register(registry);
    }
  }

  /** Called by {@link LedgerEventListener} once a batch from {@code queue} is acknowledged. */
  public void batchCommitted(String queue, int messages) {
    QueueCounts queueCounts = countsFor(queue == null ? "" : queue);
    queueCounts.batches.increment();
    queueCounts.messages.increment(messages);
    queueCounts.windowBatches.increment();
    queueCounts.windowMessages.add(messages);
  }

  @EventListener
  public void onConsumeOk(ConsumeOkEvent event) {
    setOwned(event.getQueue(), true, null);
  }

  @EventListener
  public void onConsumerFailed(ListenerContainerConsumerFailedEvent event) {
    Throwable cause = event.getThrowable();
    String reason = cause == null ? "consumer failed" : cause.getClass().getSimpleName();
    for (String queue : queuesOf(event.getSource())) {
      setOwned(queue, false, reason);
    }
  }

  @EventListener
  public void onConsumerTerminated(ListenerContainerConsumerTerminatedEvent event) {
    for (String queue : queuesOf(event.getSource())) {
      setOwned(queue, false, "consumer terminated");
    }
  }

  @EventListener
  public void onConsumerStopped(AsyncConsumerStoppedEvent event) {
    for (String queue : queuesOf(event.getSource())) {
      setOwned(queue, false, "consumer stopped");
    }
  }

  private void setOwned(String queue, boolean nowOwned, String reason) {
    AtomicBoolean flag = owned.get(queue);
    if (flag == null || flag.getAndSet(nowOwned) == nowOwned) {
      return; // not an entries queue, or no change: a standby's refusal is not news
    }
    if (nowOwned) {
      LOGGER.info("acquired entries queue {}: this pod is its exclusive consumer", queue);
    } else {
      LOGGER.info("released entries queue {} ({}): another pod may take it over", queue, reason);
    }
  }

  private static String[] queuesOf(Object source) {
    return source instanceof AbstractMessageListenerContainer container
        ? container.getQueueNames()
        : new String[0];
  }

  /**
   * Logs one summary and starts the next window. Package-private so a test can call it without
   * waiting on the schedule.
   */
  void logSummary() {
    Instant now = clock.instant();
    Duration window = Duration.between(windowStart, now);
    windowStart = now;

    long batches = 0;
    long messages = 0;
    Map<String, Long> consumed = new LinkedHashMap<>();
    for (String queue : orderedQueues()) {
      QueueCounts queueCounts = counts.get(queue);
      long queueBatches = queueCounts.windowBatches.sumThenReset();
      long queueMessages = queueCounts.windowMessages.sumThenReset();
      batches += queueBatches;
      messages += queueMessages;
      if (queueMessages > 0) {
        consumed.put(queue, queueMessages);
      }
    }

    Map<String, Long> ready = depths.ready(allQueues);
    Map<String, Long> backlog = new LinkedHashMap<>();
    List<String> notDraining = new ArrayList<>();
    for (String queue : allQueues) {
      Long depth = ready.get(queue);
      if (depth == null || depth == 0L) {
        continue;
      }
      backlog.put(queue, depth);
      if (consumesFrom(queue) && !consumed.containsKey(queue)) {
        notDraining.add(queue);
      }
    }

    LOGGER.info(
        "projection activity over {}s: {} batches, {} messages{}; entries queues owned {}/{};"
            + " cdc lag {}s; consumed [{}]; ready [{}]; not draining [{}]",
        window.toSeconds(),
        batches,
        messages,
        halt.isHalted() ? " (HALTED)" : "",
        ownedCount(),
        entriesQueues.size(),
        String.format(Locale.ROOT, "%.1f", lag.lagSeconds()),
        render(consumed),
        ready.isEmpty() ? "unavailable" : render(backlog),
        String.join(", ", notDraining));
  }

  /** The transfers queue is consumed on every running pod; an entries queue only by its owner. */
  private boolean consumesFrom(String queue) {
    if (queue.equals(transfersQueue)) {
      return true;
    }
    AtomicBoolean flag = owned.get(queue);
    return flag != null && flag.get();
  }

  private long ownedCount() {
    return owned.values().stream().filter(AtomicBoolean::get).count();
  }

  /** The entries queues this pod holds the exclusive consume on, in configured order. */
  public List<String> ownedEntriesQueues() {
    return owned.entrySet().stream()
        .filter(e -> e.getValue().get())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** Configured queues first, in order, then any other queue a batch arrived from. */
  private List<String> orderedQueues() {
    List<String> ordered = new ArrayList<>(allQueues);
    counts.keySet().stream().filter(q -> !allQueues.contains(q)).sorted().forEach(ordered::add);
    return ordered;
  }

  private static String render(Map<String, Long> perQueue) {
    return perQueue.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(", "));
  }

  private QueueCounts countsFor(String queue) {
    return counts.computeIfAbsent(
        queue,
        q ->
            new QueueCounts(
                Counter.builder(BATCHES_COUNTER)
                    .tag("queue", q)
                    .description("Batches this replica committed and acknowledged")
                    .register(registry),
                Counter.builder(MESSAGES_COUNTER)
                    .tag("queue", q)
                    .description("Messages in the batches this replica committed")
                    .register(registry)));
  }

  @Override
  public void start() {
    if (summaries == null) {
      windowStart = clock.instant();
      summaries =
          scheduler.scheduleAtFixedRate(
              this::logSummaryQuietly, Instant.now().plus(logInterval), logInterval);
    }
  }

  private void logSummaryQuietly() {
    try {
      logSummary();
    } catch (RuntimeException e) {
      // A scheduled task that throws is never run again: keep the summaries coming.
      LOGGER.warn("projection activity summary failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Shutdown releases every queue this pod still owns. spring-rabbit's own stopped event comes from
   * the consumer thread after an asynchronous stop, and on a context close it can arrive after the
   * context has stopped delivering events, so it cannot be relied on for this line.
   */
  @Override
  public void stop() {
    ScheduledFuture<?> current = summaries;
    if (current != null) {
      current.cancel(false);
      summaries = null;
    }
    for (String queue : entriesQueues) {
      setOwned(queue, false, "shutting down");
    }
  }

  @Override
  public boolean isRunning() {
    return summaries != null;
  }

  private static final class QueueCounts {
    private final Counter batches;
    private final Counter messages;
    private final LongAdder windowBatches = new LongAdder();
    private final LongAdder windowMessages = new LongAdder();

    private QueueCounts(Counter batches, Counter messages) {
      this.batches = batches;
      this.messages = messages;
    }
  }
}
