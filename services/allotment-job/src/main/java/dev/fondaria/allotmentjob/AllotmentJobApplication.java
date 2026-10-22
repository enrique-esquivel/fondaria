package dev.fondaria.allotmentjob;

import dev.fondaria.allotmentjob.config.LedgerProperties;
import dev.fondaria.allotmentjob.health.DemoClockHealthIndicator;
import dev.fondaria.allotmentjob.health.LedgerHealthIndicator;
import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
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
 * The allotment run (Task 21.2): turns a staged contribution batch into units held by participants,
 * at a struck NAV, atomically — the moment money becomes a position (phase-21-allotment.md).
 *
 * <p><b>We do not manage the portfolio and we compute no per-participant earnings.</b> This service
 * divides a cash amount by a NAV that valuation-api already struck ({@code NavPerUnit#strike},
 * unchanged), floors to the unit scale, and posts the {@code CONTRIBUTION_ALLOTMENT}
 * chart-of-accounts chain. The one thing it must never do is move a NAV: a contribution changes
 * units in issue and pool cash proportionally, never the price (docs/ENGINEERING-RULES.md §4,
 * asserted in {@code AllotmentServiceTest}).
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
public class AllotmentJobApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(AllotmentJobApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(AllotmentJobApplication.class, args);
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
