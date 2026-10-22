package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.rules.FundNav;
import dev.fondaria.valuationapi.strike.NavStrikeService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Task 20.7's fourth criterion: <b>ten funds across 365 valuation dates strike within the target
 * window, and the duration is recorded.</b>
 *
 * <p>3,650 strikes — a year of NAV history for ten funds, rebuilt from delivered files. Not a daily
 * run, which is ten strikes: this is the shape of a backfill, a replay after a correction, or the
 * demo's own accelerated clock, and it is the only one of the three where the per-strike cost is
 * visible at all.
 *
 * <h2>The target window, and what it is for</h2>
 *
 * <p><b>Five minutes.</b> The number is not a latency claim — a single strike is four round trips
 * and takes milliseconds. It is the point past which the per-strike cost has stopped being
 * constant: an accidental scan of {@code reference.fund_nav}, a units-in-issue lookup that fell
 * back to PostgreSQL, or a cache refresh that re-read every fund instead of one would each show up
 * as a curve rather than a line, and none of them is visible in a test that strikes once. The
 * budget is deliberately loose enough that a busy two-CPU runner does not fail it, and tight enough
 * that any of those three would.
 *
 * <p>The measured duration is logged on every run — that is the "recorded" half of the criterion,
 * and it is what a later change is compared against.
 *
 * <h2>These funds have no units in issue, on purpose</h2>
 *
 * <p>Every strike here divides nothing by nothing and returns the fund's declared opening price
 * (Task 20.2). That is not a weaker exercise of the path: the ledger lookup, the effective-dated
 * fund resolution, the accepted-valuation read, the append-only insert and the cache refresh all
 * happen exactly as they do for a fund with a million units. What it avoids is ten contributions
 * whose only purpose would be to make a number in an assertion less round. {@link
 * NavPublicationEndToEndTest} owns the arithmetic against real units.
 *
 * <p>Ten fresh funds and 3,650 fresh valuations per run, asserted over only their own ids
 * (AGENTS.md §4). A reused local PostgreSQL therefore grows by that much each time this runs; the
 * queries the strike issues are all indexed on {@code (fund_id, valuation_date)}, which is what
 * makes that growth uninteresting — and this test is the one that would notice if it were not.
 */
@Tag("integration")
class NavStrikeThroughputTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(NavStrikeThroughputTest.class);

  private static final int FUNDS = 10;
  private static final int VALUATION_DATES = 365;

  /** See the class javadoc. Not a latency budget — a shape budget. */
  private static final Duration TARGET_WINDOW = Duration.ofMinutes(5);

  private static PlatformStack stack;
  private static List<FundId> funds;
  private static List<LocalDate> dates;

  @BeforeAll
  static void deliverAYearOfValuations() {
    stack = PlatformStack.getInstance();
    ValuationDelivery valuations = new ValuationDelivery(stack);

    dates = businessDatesEndingToday(VALUATION_DATES);
    funds = new ArrayList<>(FUNDS);
    for (int i = 0; i < FUNDS; i++) {
      // A fund's unit ledger id must fit a TigerBeetle u32; fresh per fund, and uniquely
      // constrained across every fund that has ever existed (Task 20.1).
      FundId fundId =
          Scenario.registerFund(
              stack, ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L));
      funds.add(fundId);
      // One file per fund carrying its whole year, which is how a backfill actually arrives.
      List<ValuationDelivery.Row> rows = new ArrayList<>(VALUATION_DATES);
      for (int d = 0; d < dates.size(); d++) {
        // A pool that drifts up and down, so no two dates carry the same number and a
        // strike that quietly reused a previous row would be visible.
        rows.add(new ValuationDelivery.Row(fundId, dates.get(d), 100_000_000L + (long) d * 137L));
      }
      valuations.deliverAccepted(rows);
    }
  }

  @Test
  @DisplayName("ten funds across 365 valuation dates strike inside the target window")
  void aYearOfNavForTenFundsStrikesInsideTheTargetWindow() {
    NavStrikeService strikes = stack.valuationApiBean(NavStrikeService.class);
    List<FundNav> struck = new ArrayList<>(FUNDS * VALUATION_DATES);

    long startedAt = System.nanoTime();
    for (FundId fundId : funds) {
      for (LocalDate date : dates) {
        struck.add(strikes.strike(fundId, date));
      }
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    LOGGER.info(
        "struck {} NAVs across {} funds and {} valuation dates in {} ms ({} strikes/second)",
        struck.size(),
        FUNDS,
        VALUATION_DATES,
        elapsed.toMillis(),
        struck.size() * 1_000L / Math.max(1L, elapsed.toMillis()));

    assertThat(struck).hasSize(FUNDS * VALUATION_DATES);
    Set<Long> versions = new HashSet<>();
    for (FundNav nav : struck) {
      assertThat(versions.add(nav.navVersionId()))
          .as("every strike wrote its own row: %s on %s", nav.fundId(), nav.valuationDate())
          .isTrue();
      assertThat(nav.nav().scaled())
          .as("a fund with no units in issue strikes at its declared opening price")
          .isEqualTo(Scenario.TEN_PESOS_PER_UNIT);
    }

    assertThat(elapsed)
        .as(
            "3,650 strikes took %s ms, past the %s ms target window — the per-strike cost has"
                + " stopped being constant",
            elapsed.toMillis(), TARGET_WINDOW.toMillis())
        .isLessThan(TARGET_WINDOW);
  }

  /** The {@code count} most recent business dates, ending on the current one, oldest first. */
  private static List<LocalDate> businessDatesEndingToday(int count) {
    BusinessCalendar calendar = stack.allotmentJobBean(BusinessCalendar.class);
    List<LocalDate> collected = new ArrayList<>(count);
    LocalDate candidate = calendar.businessDateOf(Instant.now());
    for (int i = 0; i < count; i++) {
      collected.add(candidate);
      candidate = candidate.minusDays(1);
      while (!calendar.isBusinessDate(candidate)) {
        candidate = candidate.minusDays(1);
      }
    }
    return collected.reversed();
  }
}
