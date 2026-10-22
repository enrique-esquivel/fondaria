package dev.fondaria.statementworker.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Stops the worker's own listener containers, without blocking the caller (ADR-0022).
 *
 * <p>Scoped to {@link StatementJobListener#LISTENER_ID} rather than every container the registry
 * holds. {@code RabbitListenerEndpointRegistry} is process-wide — it also holds {@code
 * CloseSignedOffListener}'s single-partition scheduler container — and a poison {@code
 * statement.jobs.v1} job (a snapshot holding naming a participant {@code master.participant} has no
 * row for) is unrelated to the scheduler's own correctness. Stopping every container on the
 * registry would also stop the scheduler, silently blocking every other business date's sign-off
 * event until an operator restarts the service for a fault confined to the worker side.
 */
@Component
public class ListenerContainers {

  private static final Logger LOGGER = LoggerFactory.getLogger(ListenerContainers.class);

  private final RabbitListenerEndpointRegistry registry;

  public ListenerContainers(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  public void stopWorkerContainers() {
    MessageListenerContainer container =
        registry.getListenerContainer(StatementJobListener.LISTENER_ID);
    if (container != null && container.isRunning()) {
      LOGGER.warn("stopping listener container {}", StatementJobListener.LISTENER_ID);
      container.stop(
          () -> LOGGER.warn("listener container {} stopped", StatementJobListener.LISTENER_ID));
    }
  }
}
