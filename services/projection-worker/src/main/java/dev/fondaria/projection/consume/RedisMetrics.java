package dev.fondaria.projection.consume;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Task 30.9's RED metrics ({@code redis_request_duration_seconds{operation}} / {@code
 * redis_request_errors_total{operation}}) for {@link TraceCorrelationReader}'s outbound calls to
 * Redis/Valkey. {@code operation} is a single fixed value ({@code find}), so a tagged meter looked
 * up per call cannot grow unbounded.
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
