package dev.fondaria.eodjob;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import dev.fondaria.eodjob.config.LedgerProperties;
import dev.fondaria.eodjob.health.DemoClockHealthIndicator;
import dev.fondaria.eodjob.health.LedgerHealthIndicator;
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
 * The daily close (phase-07-eod.md): the gated chain that proves the fund balances before anything
 * is published.
 *
 * <p><b>One service for the whole chain, not one per step.</b> Task 7.2's internal reconciliation,
 * Task 7.3's custodian reconciliation, Task 7.4's dense snapshot and Task 7.5's sign-off gate are
 * four steps of one sequence in which each is only allowed to run because the one before it passed.
 * Splitting them across services would make that ordering a distributed protocol rather than a
 * method call, for no gain — they run once a day, share one business date, and nothing scales one
 * of them without the others. See {@code ADR-0018}.
 *
 * <p><b>This service reads and never writes the ledger.</b> Its whole purpose is to disagree with
 * the ledger when the projection does; a control able to post a correcting transfer could make
 * itself balance. {@code LedgerConfiguration} deliberately wires no {@code TransferIdDeriver}.
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
public class EodJobApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(EodJobApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(EodJobApplication.class, args);
  }

  /**
   * One clock for the whole service, so every recorded timestamp comes from the same source and a
   * test can substitute a fixed one rather than sleeping.
   *
   * <p>{@link StorableClock}, never {@code Clock.systemUTC()} directly: a raw system clock reports
   * nanoseconds on Linux, PostgreSQL {@code timestamptz} stores microseconds, and the difference
   * makes the same row render differently depending on whether it was answered from memory or from
   * the database. Enforced by {@code NoRawSystemClockTest}.
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
