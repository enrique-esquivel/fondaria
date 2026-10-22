package dev.fondaria.contribution.cashreceipt;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Task 30.9's RED metrics ({@code redis_request_duration_seconds{operation}} / {@code
 * redis_request_errors_total{operation}}) for this service's outbound Redis/Valkey calls — {@link
 * RedisCashReceiptResponseCache} and {@link TraceCorrelationPublisher}. {@code operation} is a
 * small, fixed set of method names ({@code find}, {@code store}, {@code publish}), so a tagged
 * meter looked up per call cannot grow unbounded.
 */
@Component
public class RedisMetrics {

  private final MeterRegistry registry;

  public RedisMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** Records one outbound call's latency, and whether it failed, tagged by {@code operation}. */
  public void requestCompleted(String operation, long elapsedNanos, boolean failed) {
    registry
        .timer("redis_request_duration_seconds", "operation", operation)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
    if (failed) {
      registry.counter("redis_request_errors_total", "operation", operation).increment();
    }
  }
}
