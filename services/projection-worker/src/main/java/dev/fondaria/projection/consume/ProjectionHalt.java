package dev.fondaria.projection.consume;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The consumer's halt latch. Set once, never cleared for the life of the process.
 *
 * <p>"Never skip a message you can't process. Send it to the DLQ and halt. A gap in a financial
 * projection is worse than downtime" (AGENTS.md §4). When {@link LedgerEventListener} hits a poison
 * message it trips this latch, raises the cluster-wide flag and hands the bytes to {@link
 * DeadLetterPublisher}, then stops the listener containers. The delivery is <b>not</b>
 * acknowledged, so the gap is closed by a deliberate restart (after a schema fix), never by the
 * consumer skipping ahead on its own.
 *
 * <p>The latch is per process. It is also tripped by {@link ClusterHalt} when it reads another
 * replica's halt from {@code workflow.projection_halt}, so a halt on one pod is a halt on all of
 * them (ADR-0032 §2). It never clears: resuming means clearing the shared row and restarting.
 *
 * <p>{@link ProjectionHaltHealthIndicator} reads this so {@code /actuator/health/readiness} reports
 * {@code DOWN} while halted — which takes the instance out of rotation and fires the page.
 */
@Component
public class ProjectionHalt {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionHalt.class);

  private final AtomicReference<String> reason = new AtomicReference<>();

  /** Trip the latch. The first reason wins; later calls are logged but do not overwrite it. */
  public void trigger(String haltReason) {
    if (reason.compareAndSet(null, haltReason)) {
      LOGGER.error("projection consumer HALTED: {}", haltReason);
    } else {
      LOGGER.error("projection consumer already halted; additional condition: {}", haltReason);
    }
  }

  public boolean isHalted() {
    return reason.get() != null;
  }

  public Optional<String> reason() {
    return Optional.ofNullable(reason.get());
  }
}
