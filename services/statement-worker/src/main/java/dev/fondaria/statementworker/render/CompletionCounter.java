package dev.fondaria.statementworker.render;

/**
 * The fan-out's "done when count == expected" signal (Task 7.5b, {@code ADR-0022} §3): one counter
 * per statement period, incremented once per participant whose statement has been rendered and
 * written — and only once, no matter how many times that participant's job message is redelivered.
 */
public interface CompletionCounter {

  /**
   * Records that {@code participantKey}'s statement for {@code period} has been rendered.
   *
   * <p>Idempotent: calling this twice for the same {@code period} and {@code participantKey} —
   * because a worker crashed after writing the object but before acknowledging the job message, and
   * the message was redelivered — increments the counter at most once. The caller does not need to
   * know whether this was the first attempt or a redelivery.
   *
   * @return the counter's value after this call
   * @throws RedisCounterUnavailableException if Redis could not be reached. The caller (the render
   *     path) has already written the statement object by the time this is called, so the object
   *     itself is never at risk — but this must not be swallowed into an acknowledged job message,
   *     because that would silently and permanently lose the increment with no path back. The
   *     exception is deliberately left uncaught here so the caller's Kafka listener does not
   *     acknowledge, and the redelivered job retries the count until Redis returns.
   */
  java.util.OptionalLong recordCompleted(String period, String participantKey);
}
