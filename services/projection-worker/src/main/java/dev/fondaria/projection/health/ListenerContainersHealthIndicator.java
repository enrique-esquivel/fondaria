package dev.fondaria.projection.health;

import java.util.Collection;
import java.util.function.Supplier;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness for the projection consumer: {@code DOWN} unless every listener container (the
 * transfers queue's and one per entries queue) is running.
 *
 * <p>{@link ProjectionHaltHealthIndicator} covers the two stops this service makes on purpose (the
 * startup gate and a halt). This covers the one it does not: a container that spring-rabbit stops
 * for good after a fatal consumer failure, such as credentials the broker refuses. Without it the
 * pod stays Ready while it consumes nothing; that is how amqp-fanout failed on the first GKE run
 * (ADR-0036, ADR-0037). An entries container on a standby pod, whose exclusive consume the broker
 * refuses because another pod owns the queue, is still running: its consumer retries every recovery
 * interval, so the standby stays {@code UP}. One the rebalance has parked (ADR-0042) is not
 * counted.
 *
 * <p>Wired into the {@code readiness} group only ({@code application.yaml}), never liveness, for
 * the reason {@link ProjectionHaltHealthIndicator} gives. {@code show-details: never} is set
 * globally, so the counts are not rendered on the probe.
 */
public class ListenerContainersHealthIndicator implements HealthIndicator {

  private final Supplier<Collection<MessageListenerContainer>> containers;

  /**
   * @param containers the containers expected to run: {@code ListenerContainers.expectedRunning},
   *     which leaves out an entries queue's container while the rebalance has stopped it on purpose
   *     (ADR-0042)
   */
  public ListenerContainersHealthIndicator(
      Supplier<Collection<MessageListenerContainer>> containers) {
    this.containers = containers;
  }

  @Override
  public Health health() {
    Collection<MessageListenerContainer> all = containers.get();
    if (all.isEmpty()) {
      return Health.down().withDetail("reason", "no listener container registered").build();
    }
    long stopped = all.stream().filter(container -> !container.isRunning()).count();
    if (stopped > 0) {
      return Health.down()
          .withDetail("stopped", stopped)
          .withDetail("containers", all.size())
          .build();
    }
    return Health.up().withDetail("containers", all.size()).build();
  }
}
