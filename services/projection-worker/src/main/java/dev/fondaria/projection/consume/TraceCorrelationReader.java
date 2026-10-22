package dev.fondaria.projection.consume;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code transferId -> traceparent} entry {@code contribution-api}'s {@code
 * TraceCorrelationPublisher} wrote, and continues that trace — rather than starting an unlinked one
 * — for the span that projects this transfer (Task 11.2, {@code docs/DESIGN-DECISIONS.md} §12).
 *
 * <p>Key format is documented there and reproduced here exactly, since services do not share source
 * trees: {@code "trace:v1:" + transferId}, where {@code transferId} is the same canonical
 * unsigned-decimal form the CDC stream already carries (see {@code TransferId#asUnsignedDecimal}).
 *
 * <p><b>Every failure is swallowed and logged, exactly like {@code RedisCashReceiptResponseCache}
 * on the other side of this same boundary.</b> A miss or an unreachable Redis costs one unlinked
 * span, never sink processing or the checkpoint advance — the property that lets this worker be
 * killed at any instant, any number of times, and still converge is unaffected either way.
 *
 * <p><b>Connected on first use, not in the constructor</b> — same reasoning as {@code
 * RedisNavSnapshotStore}: this service has no business requirement on Redis at all, so an eager
 * connection would make a Redis that happens to be down at start-up stop the one process the
 * platform promises can be killed and restarted freely. Held as a field rather than arranged with
 * Spring's {@code @Lazy} so the guarantee belongs to this class and is testable without a
 * container.
 */
public class TraceCorrelationReader implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TraceCorrelationReader.class);

  static final String KEY_PREFIX = "trace:v1:";
  static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(30);

  /** A closed, no-op result for a miss or an error — always safe to use in a try-with-resources. */
  private static final LinkedSpan NONE_SPAN = new LinkedSpan(null, null);

  /**
   * No Redis configured at all ({@link
   * dev.fondaria.projection.config.TraceRedisProperties#enabled()} is false). Every call is a
   * silent, immediate miss: unlike a configured-but-unreachable Redis, there is nothing here worth
   * a warning log on every record.
   */
  public static final TraceCorrelationReader NONE =
      new TraceCorrelationReader(
          null, null, DEFAULT_BACKOFF, dev.fondaria.common.StorableClock.systemUTC(), null, null) {
        @Override
        public LinkedSpan continueTrace(String transferId, String spanName) {
          return NONE_SPAN;
        }

        @Override
        public void close() {
          // No client was ever created.
        }
      };

  private final RedisClient client;
  private final Tracer tracer;
  private final Duration backoffDuration;
  private final Clock clock;
  private volatile StatefulRedisConnection<String, String> connection;
  private final ReentrantLock connectLock = new ReentrantLock();
  private volatile Instant backoffUntil = Instant.EPOCH;
  private final AtomicInteger backoffState = new AtomicInteger(0);
  private final RedisMetrics metrics;

  public TraceCorrelationReader(RedisClient client, Tracer tracer, RedisMetrics metrics) {
    this(client, tracer, DEFAULT_BACKOFF, dev.fondaria.common.StorableClock.systemUTC(), metrics);
  }

  public TraceCorrelationReader(
      RedisClient client,
      Tracer tracer,
      Duration backoffDuration,
      Clock clock,
      RedisMetrics metrics) {
    this(client, tracer, backoffDuration, clock, null, metrics);
  }

  @FunctionalInterface
  interface ConnectionSupplier {
    StatefulRedisConnection<String, String> get();
  }

  private final ConnectionSupplier connectionSupplier;

  TraceCorrelationReader(
      RedisClient client,
      Tracer tracer,
      Duration backoffDuration,
      Clock clock,
      ConnectionSupplier connectionSupplier,
      RedisMetrics metrics) {
    this.client = client;
    this.tracer = tracer;
    this.backoffDuration = backoffDuration;
    this.clock = clock;
    this.connectionSupplier = connectionSupplier;
    this.metrics = metrics;
  }

  /**
   * Exposes whether connection attempts are currently backed off (1 = backed off, 0 = healthy or
   * idle).
   */
  public int backoffState() {
    return backoffState.get();
  }

  /**
   * Starts a child span under the correlated trace, if one was found, and makes it current for the
   * caller's {@code try}-block. Always returns a value safe to use in a try-with-resources — on a
   * miss or any Redis error, {@link LinkedSpan#close()} is a no-op.
   */
  public LinkedSpan continueTrace(String transferId, String spanName) {
    if (inBackoff()) {
      return NONE_SPAN;
    }
    String traceparent = find(transferId);
    if (traceparent == null) {
      return NONE_SPAN;
    }
    TraceContext parent;
    try {
      parent = parse(traceparent);
    } catch (RuntimeException e) {
      LOGGER.warn("Trace correlation entry was malformed; this span will not be linked", e);
      return NONE_SPAN;
    }
    if (parent == null) {
      return NONE_SPAN;
    }
    Span span = tracer.spanBuilder().setParent(parent).name(spanName).start();
    return new LinkedSpan(span, tracer.withSpan(span));
  }

  private boolean inBackoff() {
    return connection == null && clock.instant().isBefore(backoffUntil);
  }

  private String find(String transferId) {
    StatefulRedisConnection<String, String> conn = connection();
    if (conn == null) {
      return null;
    }
    long start = System.nanoTime();
    try {
      String value = conn.sync().get(KEY_PREFIX + transferId);
      metrics.requestCompleted("find", System.nanoTime() - start, false);
      return value;
    } catch (RuntimeException e) {
      metrics.requestCompleted("find", System.nanoTime() - start, true);
      LOGGER.warn("Trace correlation entry could not be read; this span will not be linked", e);
      return null;
    }
  }

  /**
   * {@code "00-<traceId>-<spanId>-<flags>"}. Malformed input is a miss, never a decode failure —
   * shape-checked here (part count, hex length per the W3C spec) so a corrupted entry returns
   * {@code null} instead of reaching {@code traceContextBuilder()}; {@link #continueTrace} also
   * catches any exception this still throws, so a decode failure can never escape as a miss.
   */
  private TraceContext parse(String traceparent) {
    String[] parts = traceparent.split("-");
    if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
      return null;
    }
    return tracer
        .traceContextBuilder()
        .traceId(parts[1])
        .spanId(parts[2])
        .sampled("01".equals(parts[3]))
        .build();
  }

  /** Double-checked, and correct because the field is {@code volatile}. */
  private StatefulRedisConnection<String, String> connection() {
    StatefulRedisConnection<String, String> held = connection;
    if (held != null) {
      return held;
    }
    if (inBackoff()) {
      return null;
    }
    connectLock.lock();
    try {
      if (connection != null) {
        return connection;
      }
      Instant now = clock.instant();
      if (now.isBefore(backoffUntil)) {
        return null;
      }
      boolean wasBackedOff = backoffState.get() == 1;
      try {
        connection = connectionSupplier != null ? connectionSupplier.get() : client.connect();
        if (wasBackedOff) {
          LOGGER.info("Connected to trace correlation Redis; trace linking resumed");
        }
        backoffUntil = Instant.EPOCH;
        backoffState.set(0);
        return connection;
      } catch (RuntimeException e) {
        backoffUntil = now.plus(backoffDuration);
        backoffState.set(1);
        LOGGER.warn(
            "Failed to connect to trace correlation Redis; backing off for {}s: {}",
            backoffDuration.toSeconds(),
            e.getMessage());
        return null;
      }
    } finally {
      connectLock.unlock();
    }
  }

  /**
   * Releases the connection and the client. Failures are logged, never thrown out of a shutdown.
   */
  @Override
  public void close() {
    try {
      StatefulRedisConnection<String, String> held = connection;
      if (held != null) {
        held.close();
      }
      client.shutdown();
    } catch (RuntimeException e) {
      LOGGER.warn("Trace correlation reader did not shut down cleanly", e);
    }
  }

  /** Ends the linked span and closes its scope, if there is one; otherwise does nothing. */
  public record LinkedSpan(Span span, Tracer.SpanInScope scope) implements AutoCloseable {
    @Override
    public void close() {
      if (scope != null) {
        scope.close();
      }
      if (span != null) {
        span.end();
      }
    }
  }
}
