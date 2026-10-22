package dev.fondaria.statementworker;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import dev.fondaria.statementworker.health.DemoClockHealthIndicator;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The statement worker pool (phase-07-eod.md Task 7.5b; {@code ADR-0022}): the fan-out that turns a
 * signed-off month-end close into one content-hashed monthly statement per participant.
 *
 * <p><b>Two roles, one deployable</b> ({@link
 * dev.fondaria.statementworker.schedule.CloseSignedOffListener}, {@link
 * dev.fondaria.statementworker.render.StatementJobListener}) — {@code ADR-0022}'s own decision: the
 * close is a once-a-day batch and the statement workers are an always-on, horizontally-scaled
 * consumer pool, but the two roles inside this one service share nothing that benefits from being
 * split further, and running them as one Spring context means one topic subscription list and one
 * set of health probes rather than two deployments coordinating who owns which listener.
 *
 * <p><b>This service reads and never writes the ledger, and holds no {@code LedgerPort} at all.</b>
 * Every number it renders comes from Task 7.4's snapshot objects and {@code workflow.allotment}
 * joined to {@code reference.fund_nav} — see {@code ADR-0022} §4.
 */
@SpringBootApplication
@EnableScheduling
public class StatementWorkerApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatementWorkerApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(StatementWorkerApplication.class, args);
  }

  /**
   * One clock for the whole service. {@link StorableClock}, never {@code Clock.systemUTC()}
   * directly — enforced by {@code NoRawSystemClockTest} — because a raw system clock reports
   * nanoseconds on Linux while PostgreSQL {@code timestamptz} stores microseconds.
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
