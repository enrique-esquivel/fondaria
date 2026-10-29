package dev.fondaria.bridge;

import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.health.ListenerContainersHealthIndicator;
import dev.fondaria.common.StorableClock;
import java.time.Clock;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The CDC bridge (Task 4.3): the transport hop between the two brokers ADR-0002 accepts operating.
 * TigerBeetle's native CDC job (Task 4.2) publishes every committed ledger movement to RabbitMQ;
 * this service consumes {@code bridge.ledger}, explodes each change event into one transfer message
 * and an entry message for each participant-holding leg (keyed by account, so per-account ordering
 * holds on the entries topic; ADR-0040), and
 * produces them to Redpanda — the broker every downstream consumer actually reads from.
 *
 * <p><b>A pure transform.</b> It reads from one system and writes to one system. It holds no
 * database, derives no id, enforces no rule, and never reads the ledger. The only stateful thing it
 * does is decline to acknowledge a RabbitMQ message until Redpanda has confirmed the write — so a
 * crash replays rather than loses (phase-04-cdc.md). Replay of history comes from the ledger
 * ({@code --timestamp-last=0}), never from either broker, which is why the topics keep only 30
 * days.
 */
@SpringBootApplication
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(BridgeApplication.class, args);
  }

  /**
   * One clock for the whole service, so every recorded timestamp (a DLQ envelope's {@code
   * receivedAt}) comes from the same source and a test can substitute a fixed one.
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
   * id, so this registers as {@code listenerContainers} -- the name the readiness group includes
   * ({@code application.yaml}).
   */
  @Bean
  public ListenerContainersHealthIndicator listenerContainersHealthIndicator(
      RabbitListenerEndpointRegistry registry) {
    return new ListenerContainersHealthIndicator(registry);
  }
}
