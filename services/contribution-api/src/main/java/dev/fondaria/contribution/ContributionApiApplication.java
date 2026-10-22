package dev.fondaria.contribution;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import dev.fondaria.contribution.config.LedgerProperties;
import dev.fondaria.contribution.health.DemoClockHealthIndicator;
import dev.fondaria.contribution.health.LedgerHealthIndicator;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Contribution ingestion service (Phase 3): employer remittances and contribution files.
 *
 * <p>Task 3.2 built the skeleton — configuration, startup failure discipline and the health probes.
 * Task 2b.1 adds the first real capability: the organization registry, a {@code DataSource} with
 * Flyway-managed schemas, and the service's one {@code LedgerPort} (see {@code
 * config.LedgerConfiguration}).
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
public class ContributionApiApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContributionApiApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ContributionApiApplication.class, args);
  }

  /**
   * One clock for the whole service, so every recorded timestamp comes from the same source and a
   * test can substitute a fixed one rather than sleeping.
   *
   * <p>{@link StorableClock}, never {@code Clock.systemUTC()} directly: a raw system clock reports
   * nanoseconds on Linux, PostgreSQL {@code timestamptz} stores microseconds, and the difference
   * makes the same resource render differently depending on whether it was answered from memory or
   * from the database. Enforced by {@code NoRawSystemClockTest}.
   *
   * <p><b>Task D.1 — the demo's accelerated clock.</b> {@code DEMO_CLOCK_ENABLED=true} swaps in a
   * {@link SimulatedClock} polling {@code reference.demo_clock}; unset, this is the only branch
   * that ever runs and the table is never read. Production must never set the flag. Which clock was
   * built is logged once at startup, unmissably, rather than left to be inferred from behaviour
   * later.
   */
  @Bean
  public Clock clock(
      DataSource dataSource,
      @Value("${fondaria.demo-clock.enabled:false}") boolean demoClockEnabled) {
    Clock real = StorableClock.systemUTC();
    if (!demoClockEnabled) {
      LOGGER.info("Clock: StorableClock (real time; DEMO_CLOCK_ENABLED is unset)");
      return real;
    }
    LOGGER.info("Clock: SimulatedClock (DEMO_CLOCK_ENABLED=true) -- polling reference.demo_clock");
    return SimulatedClock.wrapping(real, dataSource);
  }

  /**
   * Bean name matters: Actuator strips the {@code HealthIndicator} suffix to derive the health
   * component id, so this registers as {@code ledger} — the name the readiness group includes.
   */
  @Bean
  public LedgerHealthIndicator ledgerHealthIndicator(LedgerProperties properties, Clock clock) {
    return new LedgerHealthIndicator(properties, clock);
  }

  /**
   * Bean name matters here too: registers as the {@code demoClock} health component {@code
   * phase-D-demo.md}'s Task D.1 names, for {@code demo-tools advance} (Task D.2) to poll.
   */
  @Bean
  public DemoClockHealthIndicator demoClockHealthIndicator(Clock clock) {
    return new DemoClockHealthIndicator(clock);
  }
}
