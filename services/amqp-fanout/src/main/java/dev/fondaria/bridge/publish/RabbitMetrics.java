package dev.fondaria.bridge.publish;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Task 30.9's RED metrics ({@code rabbitmq_publish_duration_seconds{destination}} / {@code
 * rabbitmq_publish_errors_total{destination}}) for {@link LedgerEventPublisher}'s outbound
 * publishes to RabbitMQ. {@code destination} is the exchange name published to — a small, fixed set
 * (the ledger topics plus the DLQ), so a tagged meter looked up per call cannot grow unbounded.
 */
@Component
public class RabbitMetrics {

  private final MeterRegistry registry;
  private final AtomicInteger halted = new AtomicInteger();

  public RabbitMetrics(MeterRegistry registry) {
    this.registry = registry;
    // ADR-0041: consumers holding a batch RabbitMQ will not confirm. Non-zero means bridge.ledger
    // is not draining, by design, until the broker confirms again.
    registry.gauge("bridge_publish_halted", halted);
  }

  /** A consumer has started holding a batch it cannot publish. */
  public void publishHalted() {
    halted.incrementAndGet();
  }

  /** That consumer has published its batch, or given it back to RabbitMQ. */
  public void publishResumed() {
    halted.decrementAndGet();
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
