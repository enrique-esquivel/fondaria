package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a short-lived {@code transferId -> traceparent} entry so {@code projection-worker} can
 * continue this request's trace once the movement reaches it over the CDC boundary (Task 11.2,
 * {@code docs/DESIGN-DECISIONS.md} §12).
 *
 * <p><b>The ledger never carries this.</b> {@code user_data_128} already carries {@link
 * dev.fondaria.ledger.ExternalReference} for a cash transfer, and a trace id is descriptive/
 * operational data, not money or a link (AGENTS.md §4) — so this bypasses the ledger and CDC
 * entirely, keyed on {@code transferId}, which every ledger event already carries untouched.
 *
 * <p><b>Every failure is swallowed and logged, exactly like {@link RedisCashReceiptResponseCache}.
 * </b> A miss or an unreachable Redis costs one unlinked trace, never a request. Nothing here is on
 * the path that decides whether a cash receipt is accepted.
 *
 * <p>Key format is documented here because {@code TraceCorrelationReader} in {@code
 * projection-worker} must reproduce it exactly (services do not share source trees) — {@code
 * "trace:v1:" + transferId.asUnsignedDecimal()}, the same decimal form the CDC stream itself
 * carries a transfer id in, so both sides agree without a lookup.
 */
public class TraceCorrelationPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(TraceCorrelationPublisher.class);

  static final String KEY_PREFIX = "trace:v1:";

  private final StatefulRedisConnection<String, String> connection;
  private final Tracer tracer;
  private final Duration ttl;
  private final RedisMetrics metrics;

  public TraceCorrelationPublisher(
      StatefulRedisConnection<String, String> connection,
      Tracer tracer,
      Duration ttl,
      RedisMetrics metrics) {
    this.connection = connection;
    this.tracer = tracer;
    this.ttl = ttl;
    this.metrics = metrics;
  }

  /**
   * Best-effort. A missing or non-sampled current span, or any Redis error, is not reported: the
   * caller has no decision to make either way, so there is nothing to throw.
   */
  public void publish(TransferId transferId) {
    Span current = tracer.currentSpan();
    if (current == null || current.isNoop()) {
      return;
    }
    TraceContext context = current.context();
    String traceparent =
        "00-"
            + context.traceId()
            + "-"
            + context.spanId()
            + "-"
            + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    long start = System.nanoTime();
    try {
      connection.sync().setex(key(transferId), ttl.toSeconds(), traceparent);
      metrics.requestCompleted("publish", System.nanoTime() - start, false);
    } catch (RuntimeException e) {
      metrics.requestCompleted("publish", System.nanoTime() - start, true);
      LOGGER.warn("Trace correlation entry could not be written; the trace will not be linked", e);
    }
  }

  private static String key(TransferId transferId) {
    return KEY_PREFIX + transferId.asUnsignedDecimal();
  }
}
