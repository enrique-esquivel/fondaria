package dev.fondaria.bridge.health;

import java.util.Collection;
import java.util.function.Supplier;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness for the bridge: {@code DOWN} unless the listener container on {@code bridge.ledger} is
 * running.
 *
 * <p>A listener container stops for good when a consumer fails fatally: a queue it cannot find
 * while {@code missing-queues-fatal} is on, credentials the broker refuses. On the first GKE run
 * (ADR-0036) that happened while the pod stayed Ready, so every ledger event piled up unread on
 * {@code bridge.ledger} and the projection silently received nothing. This makes that state a pod
 * that is not Ready (ADR-0037). A container whose consumers are still retrying, a broker that is
 * restarting, say, is running and stays {@code UP}: Spring reconnects it on its own.
 *
 * <p>Wired into the {@code readiness} group only ({@code application.yaml}), never liveness: a pod
 * that restarts on its own hides the cause from the operator who has to fix it.
 *
 * <p>{@code show-details: never} is set globally, so the counts are not rendered on the probe.
 */
public class ListenerContainersHealthIndicator implements HealthIndicator {

  private final Supplier<Collection<MessageListenerContainer>> containers;

  public ListenerContainersHealthIndicator(RabbitListenerEndpointRegistry registry) {
    this(registry::getListenerContainers);
  }

  ListenerContainersHealthIndicator(Supplier<Collection<MessageListenerContainer>> containers) {
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
