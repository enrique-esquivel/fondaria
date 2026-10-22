package dev.fondaria.valuationapi.objectstore;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Task 30.9's RED metrics ({@code s3_request_duration_seconds{operation}} / {@code
 * s3_request_errors_total{operation}}) for {@link S3ObjectStore}'s outbound calls to the object
 * store. {@code operation} is a small, fixed set of method names ({@code head}, {@code get}), so a
 * tagged meter looked up per call cannot grow unbounded. Presigning is excluded: it is a local
 * signing operation, not a network call to the object store (see {@link
 * S3ObjectStore#presignUpload}).
 */
@Component
public class S3Metrics {

  private final MeterRegistry registry;

  public S3Metrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** Records one outbound call's latency, and whether it failed, tagged by {@code operation}. */
  public void requestCompleted(String operation, long elapsedNanos, boolean failed) {
    registry
        .timer("s3_request_duration_seconds", "operation", operation)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
    if (failed) {
      registry.counter("s3_request_errors_total", "operation", operation).increment();
    }
  }
}
