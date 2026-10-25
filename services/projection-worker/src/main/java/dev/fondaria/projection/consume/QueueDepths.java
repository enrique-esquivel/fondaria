package dev.fondaria.projection.consume;

import java.util.List;
import java.util.Map;

/**
 * Reads how many messages are ready on each projection queue, for {@link ProjectionActivity}'s
 * summary line. The broker's own {@code rabbitmq_detailed_queue_messages} stays the metric; this
 * exists so the backlog is visible in this service's logs where no Prometheus runs (ADR-0035).
 */
@FunctionalInterface
public interface QueueDepths {

  /**
   * Ready messages per queue, in {@code queues}' order. A queue whose depth could not be read is
   * absent. Never throws: an empty map means the broker could not be asked in time.
   */
  Map<String, Long> ready(List<String> queues);
}
