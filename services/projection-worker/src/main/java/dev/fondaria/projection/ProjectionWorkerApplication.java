package dev.fondaria.projection;

import dev.fondaria.common.StorableClock;
import dev.fondaria.projection.consume.ClusterHalt;
import dev.fondaria.projection.consume.ProjectionHalt;
import dev.fondaria.projection.health.ListenerContainersHealthIndicator;
import dev.fondaria.projection.health.ProjectionHaltHealthIndicator;
import java.time.Clock;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The CDC projection consumer (Phase 5): reads {@code ledger.transfers.v1} and {@code
 * ledger.entries.v1} from RabbitMQ (ADR-0022) and maintains the append-only {@code projection}
 * schema so that balances-as-history, statements and reconciliation have a warm store to read
 * (docs/DOMAIN.md). A balance itself is still read from {@code LedgerPort}, never from here
 * (ADR-0008).
 *
 * <p><b>The property Phase 5 is built around:</b> kill this process at any instant, any number of
 * times, and it converges to exactly the right state. Manual acknowledgement after the write,
 * idempotent inserts (Task 5.4), a monotonic-timestamp guard on the sparse unit position (Task
 * 5.5), and — when a message cannot be decoded at all — dead-letter the bytes and <i>halt</i>,
 * because a gap in a financial projection is worse than downtime (docs/ENGINEERING-RULES.md §4).
 *
 * <p>Task 5.3 ships the consumer skeleton: the ordering, acknowledgement, retry and halt machinery,
 * with a no-op sink. Tasks 5.4 and 5.5 add the Postgres writes.
 */
@SpringBootApplication
// ProjectionCheckpoint#removeStale prunes idle checkpoint rows (ADR-0032 §1).
@EnableScheduling
public class ProjectionWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProjectionWorkerApplication.class, args);
  }

  /**
   * One clock for the whole service, so every recorded timestamp (a dead-letter envelope's {@code
   * observedAt}) comes from the same source and a test can substitute a fixed one.
   *
   * <p>{@link StorableClock}, never {@code Clock.systemUTC()} directly: a raw system clock reports
   * nanoseconds on Linux, PostgreSQL {@code timestamptz} stores microseconds, and the difference
   * makes the same resource render differently depending on whether it was answered from memory or
   * from the database. Enforced by {@code NoRawSystemClockTest}.
   */
  @Bean
  public Clock clock() {
    return StorableClock.systemUTC();
  }

  /**
   * Bean name matters: Actuator strips the {@code HealthIndicator} suffix to derive the component
   * id, so this registers as {@code projectionHalt} — the name the readiness group includes ({@code
   * application.yaml}).
   */
  @Bean
  public ProjectionHaltHealthIndicator projectionHaltHealthIndicator(
      ProjectionHalt halt, ClusterHalt clusterHalt) {
    return new ProjectionHaltHealthIndicator(halt, clusterHalt::consumersStarted);
  }

  /** Registers as {@code listenerContainers}, the name the readiness group includes. */
  @Bean
  public ListenerContainersHealthIndicator listenerContainersHealthIndicator(
      RabbitListenerEndpointRegistry registry) {
    return new ListenerContainersHealthIndicator(registry);
  }
}
