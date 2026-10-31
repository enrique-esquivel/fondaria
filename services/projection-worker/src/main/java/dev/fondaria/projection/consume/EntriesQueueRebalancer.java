package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.ProjectionConsumerProperties;
import dev.fondaria.projection.config.RebalanceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Spreads the entries queues across the replicas (ADR-0042): a pod at its fair share stops
 * competing for more, and a pod over it gives the excess back.
 *
 * <p>Each entries queue has one exclusive consumer, granted to whichever pod asks first, so the
 * first pod to start takes all of them; on GKE run 3 one pod owned 16 of 16 at its 1-CPU limit.
 * Every {@code PROJECTION_REBALANCE_INTERVAL} this pod reads how many pods are consuming and
 * compares what it owns with {@code ceil(queues / pods)}:
 *
 * <ul>
 *   <li><b>At or over its share</b>, it parks (stops) every entries container whose queue it does
 *       not own, so its standbys stop competing; over its share, it also parks the excess queues it
 *       owns. The pods under their share are then the only ones whose standbys ask, and they take
 *       what was released. A first build that only released, with every standby still asking,
 *       passed the same four queues back and forth between two over-share pods on kind.
 *   <li><b>Under its share</b>, it starts every parked container again, so a dead pod's queues are
 *       taken over on the next pass.
 * </ul>
 *
 * <p><b>Live pods, from the broker.</b> Every pod runs {@code PROJECTION_TRANSFER_CONSUMERS}
 * competing consumers on the transfers queue, so its consumer count divided by that, rounded down,
 * is the number of pods consuming now. Parking acts only when two consecutive reads agree, so a
 * surge pod or a restarting consumer cannot shrink anyone's share for one pass; unparking, the safe
 * direction, acts on a single read. An unreadable count skips the pass.
 *
 * <p><b>Correct under any ownership change.</b> Stopping a container cancels its consume before its
 * in-flight batch commits, so for a few seconds two consumers can hold legs of one holding; the 26w
 * amendment already relies on {@link ParticipantUnitsProjection}'s advisory lock and
 * timestamp-guarded upsert (ADR-0024) for exactly that. Nothing happens while the projection is
 * halted (ADR-0032).
 */
@Component
public class EntriesQueueRebalancer implements SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(EntriesQueueRebalancer.class);

  static final String RELEASES_COUNTER = "projection_rebalance_releases_total";

  private final ProjectionActivity activity;
  private final ListenerContainers containers;
  private final QueueConsumers consumers;
  private final ProjectionHalt halt;
  private final TaskScheduler scheduler;
  private final String transfersQueue;
  private final int transferConsumersPerPod;
  private final List<String> entriesQueues;
  private final RebalanceProperties properties;
  private final Counter releases;

  /**
   * Live pods from the previous read; 0 when there was none, or it could not be used. Only the
   * scheduler's one thread runs a pass, so this needs no lock.
   */
  private int previousLivePods;

  private volatile ScheduledFuture<?> passes;

  public EntriesQueueRebalancer(
      ProjectionActivity activity,
      ListenerContainers containers,
      QueueConsumers consumers,
      ProjectionHalt halt,
      TaskScheduler scheduler,
      ProjectionConsumerProperties consumerProperties,
      RebalanceProperties properties,
      MeterRegistry registry) {
    this.activity = activity;
    this.containers = containers;
    this.consumers = consumers;
    this.halt = halt;
    this.scheduler = scheduler;
    this.transfersQueue = consumerProperties.transfersQueue();
    this.transferConsumersPerPod = consumerProperties.transferConsumers();
    this.entriesQueues = consumerProperties.entriesQueueNames();
    this.properties = properties;
    this.releases =
        Counter.builder(RELEASES_COUNTER)
            .description(
                "Entries queues this replica gave back because it owned more than its share")
            .register(registry);
  }

  /** {@code ceil(queues / pods)}. */
  static int fairShare(int queues, int livePods) {
    return Math.ceilDiv(queues, Math.max(1, livePods));
  }

  /** Pods consuming now, from the transfers queue's consumer count; at least 1 (this one). */
  static int livePods(int transfersQueueConsumers, int transferConsumersPerPod) {
    return Math.max(1, transfersQueueConsumers / transferConsumersPerPod);
  }

  /** One pass. Package-private so a test can run it without the schedule. */
  void rebalance() {
    if (halt.isHalted()) {
      previousLivePods = 0;
      return;
    }
    OptionalInt transfersConsumers = consumers.count(transfersQueue);
    if (transfersConsumers.isEmpty()) {
      previousLivePods = 0;
      return;
    }
    int livePods = livePods(transfersConsumers.getAsInt(), transferConsumersPerPod);
    boolean confirmed = livePods == previousLivePods;
    previousLivePods = livePods;

    List<String> owned = activity.ownedEntriesQueues();
    int share = fairShare(entriesQueues.size(), livePods);
    if (owned.size() < share) {
      standByAgain(owned.size(), share, livePods);
    } else if (confirmed) {
      stopCompeting(owned, share, livePods);
    }
  }

  private void standByAgain(int owned, int share, int livePods) {
    int unparked = containers.unparkAll();
    if (unparked > 0) {
      LOGGER.info(
          "standing by for {} entries queues again: this pod owns {}, its share is {} of {}"
              + " across {} pods",
          unparked,
          owned,
          share,
          entriesQueues.size(),
          livePods);
    }
  }

  private void stopCompeting(List<String> owned, int share, int livePods) {
    for (String queue : owned.subList(share, owned.size())) {
      if (containers.park(queue)) {
        releases.increment();
        LOGGER.info(
            "released entries queue {}: this pod owned {}, its share is {} of {} across {} pods",
            queue,
            owned.size(),
            share,
            entriesQueues.size(),
            livePods);
      }
    }
    int standbys = 0;
    for (String queue : entriesQueues) {
      if (!owned.contains(queue) && containers.park(queue)) {
        standbys++;
      }
    }
    if (standbys > 0) {
      LOGGER.info(
          "parked {} standby consumers: this pod is at its share of {} entries queues",
          standbys,
          share);
    }
  }

  private void rebalanceQuietly() {
    try {
      rebalance();
    } catch (RuntimeException e) {
      // A scheduled task that throws is never run again: keep the passes coming.
      LOGGER.warn("entries queue rebalance failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public void start() {
    if (passes == null && properties.enabled()) {
      passes =
          scheduler.scheduleAtFixedRate(
              this::rebalanceQuietly,
              Instant.now().plus(properties.interval()),
              properties.interval());
    }
  }

  @Override
  public void stop() {
    ScheduledFuture<?> current = passes;
    if (current != null) {
      current.cancel(false);
      passes = null;
    }
  }

  @Override
  public boolean isRunning() {
    return passes != null;
  }
}
