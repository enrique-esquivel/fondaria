package dev.fondaria.valuationapi;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import dev.fondaria.valuationapi.config.LedgerProperties;
import dev.fondaria.valuationapi.health.DemoClockHealthIndicator;
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
 * Valuation ingestion service: receives what an external investment platform reports a fund's
 * pooled assets are worth, validates it, and records it (Task 20.3); divides it by units in issue
 * read from the ledger to strike a NAV (Task 20.4).
 *
 * <p><b>We do not manage the portfolio.</b> Nothing in this service prices a security or computes a
 * per-participant amount. The one division it performs is {@code NavPerUnit#strike}, unchanged from
 * Task 20.2 (docs/ENGINEERING-RULES.md §2).
 *
 * <p>The {@code LedgerPort} this service holds ({@code config.LedgerConfiguration}) is read-only in
 * practice: {@code NavStrikeService} only ever calls {@code lookupAccount}. Units in issue are read
 * at strike time, not at ingestion time, and a valuation file has no opinion about them.
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
public class ValuationApiApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(ValuationApiApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ValuationApiApplication.class, args);
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
   * Bean name matters: registers as the {@code demoClock} health component {@code phase-D-demo.md}
   * 's Task D.1 names, for {@code demo-tools advance} (Task D.2) to poll.
   */
  @Bean
  public DemoClockHealthIndicator demoClockHealthIndicator(Clock clock) {
    return new DemoClockHealthIndicator(clock);
  }
}
