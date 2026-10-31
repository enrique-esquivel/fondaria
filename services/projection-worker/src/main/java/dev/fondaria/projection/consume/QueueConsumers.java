package dev.fondaria.projection.consume;

import java.util.OptionalInt;

/**
 * Reads how many consumers the broker has registered on a queue, for {@link
 * EntriesQueueRebalancer}'s count of live replicas (ADR-0042).
 */
@FunctionalInterface
public interface QueueConsumers {

  /**
   * The broker's consumer count for {@code queue}. Never throws: empty means the broker could not
   * be asked in time, or the queue does not exist.
   */
  OptionalInt count(String queue);
}
