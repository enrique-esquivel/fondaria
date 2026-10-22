package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.HaltProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Makes "halt" mean the whole worker, not one pod (ADR-0032 §2, row 26f).
 *
 * <p>Three jobs, all against {@code workflow.projection_halt} through {@link ClusterHaltFlag}:
 *
 * <ul>
 *   <li><b>Raise.</b> {@link #raise} is what {@link LedgerEventListener} calls on a poison message,
 *       <i>before</i> the dead-letter publish, because the flag is what stops the other replicas
 *       and the publish can take up to its confirm timeout. The local latch is already tripped by
 *       then, so a database that cannot be reached leaves this pod halted regardless; the insert is
 *       retried in the background, with a capped backoff, until it lands.
 *   <li><b>Watch.</b> Every {@code PROJECTION_HALT_POLL_INTERVAL} each replica reads the flag. On
 *       an active halt it trips its own {@link ProjectionHalt} latch and stops its listener
 *       containers, so readiness reports {@code DOWN} too. Other replicas therefore stop consuming
 *       within one interval plus one in-flight transaction per consumer.
 *   <li><b>Gate startup.</b> The listener containers are not auto-started ({@code
 *       AmqpConsumeConfiguration}). They start on the first poll that succeeds and finds no active
 *       halt, so a pod scheduled during a halt never consumes — and never starts at all while the
 *       database cannot be reached.
 * </ul>
 *
 * <p><b>Resuming stays deliberate.</b> The in-process latch never clears. When an operator clears
 * the row the halted pods stay halted until a rollout restart; new pods find no active halt and
 * start. A poll that fails changes nothing: it neither halts a healthy pod nor resumes a halted
 * one. An event that arrives during the window between a raise and the next poll is safe for the
 * final state, because the projection is order-independent ({@code ADR-0024}).
 *
 * <p>Also publishes {@code projection_halted} (0/1 per pod, from the latch); alert on {@code
 * max(projection_halted) > 0} to page once for a cluster halt.
 */
@Component
public class ClusterHalt implements SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterHalt.class);

  /** The 0/1 per-pod gauge fed from the latch. */
  static final String HALTED_GAUGE = "projection_halted";

  private static final Duration RAISE_RETRY_INITIAL = Duration.ofMillis(500);
  private static final Duration RAISE_RETRY_MAX = Duration.ofSeconds(10);

  private final ClusterHaltFlag flag;
  private final ProjectionHalt halt;
  private final ListenerContainers containers;
  private final TaskScheduler scheduler;
  private final Duration pollInterval;
  private final AtomicBoolean consumersStarted = new AtomicBoolean();

  private volatile ScheduledFuture<?> poller;
  private volatile boolean stopping;

  public ClusterHalt(
      ClusterHaltFlag flag,
      ProjectionHalt halt,
      ListenerContainers containers,
      TaskScheduler scheduler,
      HaltProperties properties,
      MeterRegistry meterRegistry) {
    this.flag = flag;
    this.halt = halt;
    this.containers = containers;
    this.scheduler = scheduler;
    this.pollInterval = properties.pollInterval();
    Gauge.builder(HALTED_GAUGE, halt, h -> h.isHalted() ? 1.0 : 0.0)
        .description("1 while this projection-worker replica is halted, else 0")
        .register(meterRegistry);
  }

  /**
   * Share this pod's halt with every replica. Returns as soon as the first attempt has run; if it
   * could not reach the database, a background retry carries on until the row is in place. Never
   * throws: the caller must go on to publish the poison bytes and stop its containers.
   */
  public void raise(String reasonCode, String sourceQueue) {
    attemptRaise(reasonCode, sourceQueue, RAISE_RETRY_INITIAL);
  }

  private void attemptRaise(String reasonCode, String sourceQueue, Duration nextBackoff) {
    try {
      boolean inserted = flag.raise(reasonCode, sourceQueue);
      if (inserted) {
        LOGGER.error("cluster halt raised ({}); every replica will stop", reasonCode);
      } else {
        LOGGER.error(
            "a cluster halt was already active; this pod's poison ({}) adds none", reasonCode);
      }
    } catch (RuntimeException e) {
      // A driver message can carry a hostname (AGENTS.md §7): log the type only.
      LOGGER.error(
          "could not raise the cluster halt ({}); this pod stays halted, retrying in {}",
          e.getClass().getSimpleName(),
          nextBackoff);
      Duration following =
          nextBackoff.multipliedBy(2).compareTo(RAISE_RETRY_MAX) > 0
              ? RAISE_RETRY_MAX
              : nextBackoff.multipliedBy(2);
      scheduler.schedule(
          () -> attemptRaise(reasonCode, sourceQueue, following), Instant.now().plus(nextBackoff));
    }
  }

  /**
   * One read of the flag. Package-private so a test can drive it without waiting on the schedule.
   */
  void poll() {
    try {
      Optional<ClusterHaltFlag.ActiveHalt> active = flag.active();
      if (active.isPresent()) {
        onActiveHalt(active.get());
      } else {
        startConsumersOnce();
      }
    } catch (RuntimeException e) {
      LOGGER.warn("halt flag poll failed: {}", e.getClass().getSimpleName());
    }
  }

  private void onActiveHalt(ClusterHaltFlag.ActiveHalt active) {
    if (halt.isHalted()) {
      return; // already latched: locally raised, or seen on an earlier poll
    }
    halt.trigger(
        "cluster halt %d (%s at queue %s) raised by %s"
            .formatted(
                active.haltId(), active.reasonCode(), active.sourceQueue(), active.haltedBy()));
    containers.stopAll();
    LOGGER.error("projection halted by the cluster-wide flag; readiness will report DOWN");
  }

  private void startConsumersOnce() {
    if (stopping || halt.isHalted() || !consumersStarted.compareAndSet(false, true)) {
      return;
    }
    try {
      containers.startAll();
      LOGGER.info("no active cluster halt; projection consumers started");
    } catch (RuntimeException e) {
      consumersStarted.set(false); // the next poll tries again
      throw e;
    }
  }

  /**
   * Whether the startup gate has opened: a poll has succeeded, found no active halt and started the
   * listener containers. Readiness stays {@code DOWN} until then, so a pod whose polls keep failing
   * (an unapplied migration, a missing grant) is not reported ready while it consumes nothing.
   */
  public boolean consumersStarted() {
    return consumersStarted.get();
  }

  @Override
  public void start() {
    stopping = false;
    if (poller == null) {
      poller = scheduler.scheduleWithFixedDelay(this::poll, Instant.now(), pollInterval);
    }
  }

  @Override
  public void stop() {
    // A poll already running is not waited for; it must not restart containers the registry is
    // stopping.
    stopping = true;
    ScheduledFuture<?> current = poller;
    if (current != null) {
      current.cancel(false);
      poller = null;
    }
  }

  @Override
  public boolean isRunning() {
    return poller != null;
  }
}
