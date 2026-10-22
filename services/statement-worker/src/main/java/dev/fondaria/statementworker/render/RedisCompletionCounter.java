package dev.fondaria.statementworker.render;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CompletionCounter} over Redis, via Lettuce (Task 7.5b, {@code ADR-0022} §3).
 *
 * <p><b>{@code SADD}-then-{@code INCR}, in one script, for the same reason {@code
 * RedisNavSnapshotStore} scripts its read-then-write:</b> a job message may be redelivered (a
 * worker crash between writing the statement object and acknowledging), and two plain commands
 * would let two deliveries both pass a "have I seen this participant" check before either recorded
 * having seen it. Scripted, only the delivery that actually adds the participant to the set
 * increments the count.
 *
 * <p><b>Integers only, {@code INCR} never {@code INCRBYFLOAT}</b> (docs/ENGINEERING-RULES.md §4).
 *
 * <p><b>{@link #recordCompleted} propagates a Redis failure; {@link #currentCount} swallows
 * one.</b> The two calls have different consumers and different correct behaviour on an outage.
 * {@link #recordCompleted} is called from the worker's message handler, and its caller must not
 * acknowledge the job on a failed count — the {@link RedisCounterUnavailableException} it throws is
 * transient (never added to a Kafka error handler's permanent-failure list), so the job is
 * redelivered and retried until Redis returns, rather than the increment being silently and
 * permanently lost. {@link #currentCount} is read by a periodic sweep that already tolerates "not
 * yet" by construction — one missed sweep costs nothing, so it logs and moves on rather than
 * blocking anything on Redis's availability. An earlier version swallowed both, which let a Redis
 * outage acknowledge job messages anyway and permanently under-count the run with no path back.
 */
public class RedisCompletionCounter implements CompletionCounter {

  private static final Logger LOGGER = LoggerFactory.getLogger(RedisCompletionCounter.class);

  /**
   * KEYS[1] the seen-participants set for this period · KEYS[2] the count key for this period.
   * ARGV[1] the participant key · ARGV[2] the TTL in milliseconds, re-applied on every call so a
   * slow run's keys never expire mid-flight.
   *
   * <p>Returns the counter's current value, whether or not this call was the one that incremented
   * it — the caller only needs "where does the count stand now", not "did I just move it".
   */
  private static final String RECORD_COMPLETED =
      """
      local added = redis.call('SADD', KEYS[1], ARGV[1])
      if added == 1 then
        redis.call('INCR', KEYS[2])
      end
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      redis.call('PEXPIRE', KEYS[2], ARGV[2])
      return redis.call('GET', KEYS[2])
      """;

  private final RedisClient client;
  private final long counterTtlMillis;
  private final RedisMetrics metrics;

  private volatile StatefulRedisConnection<String, String> connection;
  private final ReentrantLock connectLock = new ReentrantLock();

  public RedisCompletionCounter(RedisClient client, Duration counterTtl, RedisMetrics metrics) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    Objects.requireNonNull(counterTtl, "counterTtl must not be null");
    this.counterTtlMillis = counterTtl.toMillis();
    this.metrics = metrics;
  }

  /**
   * @throws RedisCounterUnavailableException if Redis could not be reached — transient, retried.
   */
  @Override
  public OptionalLong recordCompleted(String period, String participantKey) {
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(participantKey, "participantKey must not be null");
    long start = System.nanoTime();
    try {
      String value =
          connection()
              .sync()
              .eval(
                  RECORD_COMPLETED,
                  ScriptOutputType.VALUE,
                  new String[] {seenKey(period), countKey(period)},
                  participantKey,
                  Long.toString(counterTtlMillis));
      OptionalLong result =
          value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
      metrics.requestCompleted("recordCompleted", System.nanoTime() - start, false);
      return result;
    } catch (RuntimeException e) {
      metrics.requestCompleted("recordCompleted", System.nanoTime() - start, true);
      throw new RedisCounterUnavailableException(
          "statement completion counter unavailable for period " + period, e);
    }
  }

  /**
   * The counter's current value, or empty if Redis could not be reached. For the settlement sweep.
   */
  public OptionalLong currentCount(String period) {
    Objects.requireNonNull(period, "period must not be null");
    long start = System.nanoTime();
    try {
      String value = connection().sync().get(countKey(period));
      OptionalLong result =
          value == null ? OptionalLong.of(0L) : OptionalLong.of(Long.parseLong(value));
      metrics.requestCompleted("currentCount", System.nanoTime() - start, false);
      return result;
    } catch (RuntimeException e) {
      metrics.requestCompleted("currentCount", System.nanoTime() - start, true);
      LOGGER.warn("statement completion counter unavailable for period {}", period, e);
      return OptionalLong.empty();
    }
  }

  private static String seenKey(String period) {
    return "statement:v1:{stmt:" + period + "}:seen";
  }

  private static String countKey(String period) {
    return "statement:v1:{stmt:" + period + "}:count";
  }

  private StatefulRedisConnection<String, String> connection() {
    StatefulRedisConnection<String, String> held = connection;
    if (held != null) {
      return held;
    }
    connectLock.lock();
    try {
      if (connection == null) {
        connection = client.connect();
      }
      return connection;
    } finally {
      connectLock.unlock();
    }
  }

  public void close() {
    try {
      StatefulRedisConnection<String, String> held = connection;
      if (held != null) {
        held.close();
      }
      client.shutdown();
    } catch (RuntimeException e) {
      LOGGER.warn("statement completion counter did not shut down cleanly", e);
    }
  }
}
