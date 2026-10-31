package dev.fondaria.projection.consume;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>{@link #park} and {@link #unparkAll} stop and restart entries queues' containers for {@link
 * EntriesQueueRebalancer} (ADR-0042). A parked container is stopped on purpose, so {@link
 * #expectedRunning} leaves it out and readiness stays {@code UP}.
 */
@Component
public class ListenerContainers {

  private static final Logger LOGGER = LoggerFactory.getLogger(ListenerContainers.class);

  private final RabbitListenerEndpointRegistry registry;
  private final Set<MessageListenerContainer> parked = ConcurrentHashMap.newKeySet();

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

  /**
   * Stops {@code queue}'s entries container, non-blocking, and keeps it stopped until {@link
   * #unparkAll}. Returns {@code false} if it is not running, not registered, or already parked.
   */
  public boolean park(String queue) {
    MessageListenerContainer container = entriesContainer(queue);
    if (container == null || !container.isRunning() || !parked.add(container)) {
      return false;
    }
    container.stop(() -> LOGGER.debug("entries container parked"));
    return true;
  }

  /** Starts every parked container again. Returns how many were parked. */
  public int unparkAll() {
    int unparked = 0;
    for (MessageListenerContainer container : List.copyOf(parked)) {
      if (!container.isRunning()) {
        container.start();
      }
      parked.remove(container);
      unparked++;
    }
    return unparked;
  }

  /** Every registered container except the parked ones: the readiness set. */
  public Collection<MessageListenerContainer> expectedRunning() {
    List<MessageListenerContainer> expected =
        registry.getListenerContainers().stream()
            .filter(container -> !parked.contains(container))
            .toList();
    return expected;
  }

  private MessageListenerContainer entriesContainer(String queue) {
    return registry.getListenerContainer(EntriesQueueListeners.ENDPOINT_ID_PREFIX + queue);
  }
}
