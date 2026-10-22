package dev.fondaria.eodjob.signoff;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Task 30.9's RED metrics ({@code rabbitmq_publish_duration_seconds{destination}} / {@code
 * rabbitmq_publish_errors_total{destination}}) for {@link AmqpCloseSignedOffPublisher}'s outbound
 * publish to RabbitMQ. {@code destination} is the fixed exchange name this service publishes to, so
 * a tagged meter looked up per call cannot grow unbounded.
 */
@Component
public class RabbitMetrics {

  private final MeterRegistry registry;

  public RabbitMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Records one outbound publish's latency, and whether it failed, tagged by {@code destination}.
   */
  public void publishCompleted(String destination, long elapsedNanos, boolean failed) {
    registry
        .timer("rabbitmq_publish_duration_seconds", "destination", destination)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
    if (failed) {
      registry.counter("rabbitmq_publish_errors_total", "destination", destination).increment();
    }
  }
}
