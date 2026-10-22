package dev.fondaria.balanceapi;

import dev.fondaria.balanceapi.config.LedgerProperties;
import dev.fondaria.balanceapi.health.DemoClockHealthIndicator;
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
 * The read side: what a participant holds, and what it is worth (Phase 6, Task 6.2).
 *
 * <p><b>The balance is a multiplication, not a query</b> (docs/ENGINEERING-RULES.md §2). Units come
 * from the ledger — {@code LedgerPort.lookupAccounts}, O(1) per account and batched into one round
 * trip — and the NAV per unit comes from an in-memory cache holding one number per fund. Value is
 * {@code units × NAV}, computed on read by {@code Money.value} and never stored, never summed out
 * of PostgreSQL.
 *
 * <p><b>That is why PostgreSQL is not in this path.</b> The only thing this service reads from the
 * database on behalf of a request is the {@code participantId → (organizationId,
 * participantInternalRef)} resolution, and that mapping is immutable, so it is cached and the read
 * happens once per participant per process. With PostgreSQL stopped, a balance for any participant
 * already resolved is still served, correctly and from live ledger state. The NAV cache converges
 * on {@code reference.fund_nav} on its own interval and holds its last known prices meanwhile,
 * marked stale once they age past the configured bound.
 *
 * <p><b>Cash awaiting allotment is deliberately not reported here.</b> Money an employer has wired
 * but whose dispersion file has not yet arrived has no per-participant split at all — it is one
 * lump sum in {@code EMPLOYER_COLLECTIONS} — and after the file arrives but before the
 * reconciliation gate passes, the per-participant amounts are declared rather than confirmed. A
 * provisional number that can still change is worse than no number, so a participant sees units
 * they actually hold and nothing else. The employer-facing view of money in flight already exists,
 * on the contribution batch's own status resource ({@code awaitingAllotment} and {@code
 * dealingDate}, Task 21.1).
 *
 * <p><b>Unvested units are reported, and separately.</b> They are issued, priced, and they move
 * with the market exactly as vested units do; vesting governs entitlement, never whether a unit is
 * valued (ADR-0004).
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
public class BalanceApiApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(BalanceApiApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(BalanceApiApplication.class, args);
  }

  /**
   * One clock for the whole service, so {@code asOf} on a response and the staleness judgement
   * applied to the prices behind it come from the same source, and a test can fix it rather than
   * sleep.
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
