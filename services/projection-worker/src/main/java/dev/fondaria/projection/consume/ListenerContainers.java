package dev.fondaria.projection.consume;

import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Starts and stops every Rabbit listener container.
 *
 * <p>{@link #stopAll} is used on the poison path and when a cluster halt is observed: once {@link
 * ProjectionHalt} is tripped, the consumer must take in nothing more. {@code stop(Runnable)} is the
 * non-blocking form — calling the blocking {@code stop()} from a listener thread would deadlock,
 * because that thread is the one the container is waiting to finish.
 *
 * <p>{@link #startAll} is the startup gate's release ({@link ClusterHalt}): the containers are
 * declared with auto-startup off so that nothing is consumed before the halt flag has been read.
 */
@Component
public class ListenerContainers {

  private static final Logger LOGGER = LoggerFactory.getLogger(ListenerContainers.class);

  private final RabbitListenerEndpointRegistry registry;

  public ListenerContainers(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  public void startAll() {
    for (MessageListenerContainer container : registry.getListenerContainers()) {
      if (!container.isRunning()) {
        container.start();
      }
    }
  }

  public void stopAll() {
    Collection<MessageListenerContainer> containers = registry.getListenerContainers();
    for (MessageListenerContainer container : containers) {
      if (container.isRunning()) {
        LOGGER.warn("stopping listener container");
        container.stop(() -> LOGGER.warn("listener container stopped"));
      }
    }
  }
}
