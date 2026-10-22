package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Task 21.5's third and fourth criteria: across a <b>volatile NAV series including falling
 * prices</b>, participants contributing on different dates receive correctly different unit counts,
 * a later joiner receives none of an earlier joiner's gain, and a participant whose value has
 * fallen below what they contributed shows a <b>negative</b> gain, unclamped.
 *
 * <h2>Ana and Bruno, and then the fall</h2>
 *
 * <p>{@code docs/DOMAIN.md} §1.1 tells this story rising: Ana buys at one price, the pool grows,
 * Bruno buys at the grown price and gets fewer units for the same money, so Bruno receives none of
 * Ana's gain. This suite tells it with the pool falling afterwards as well, because a suite that
 * only ever raises the price passes on a broken implementation (AGENTS.md §4) — and because a fall
 * is the case where the temptation to clamp a figure at zero exists at all.
 *
 * <p>Four participants wire an identical MXN 15,000.00 into <b>one fund</b> on four dealing dates,
 * priced MXN 10.00 → 12.50 → 8.00 → 6.25. Every unit count below is exact at these prices, so a
 * wrong figure is a wrong figure rather than a rounding argument — Task 21.3 owns the remainder.
 *
 * <table>
 *   <caption>What each participant's identical wire buys, and what it is worth at the end</caption>
 *   <tr><th>Joins at</th><th>Units</th><th>Value at MXN 6.25</th><th>Gain</th></tr>
 *   <tr><td>MXN 10.00</td><td>1,500</td><td>MXN 9,375.00</td><td>−MXN 5,625.00</td></tr>
 *   <tr><td>MXN 12.50</td><td>1,200</td><td>MXN 7,500.00</td><td>−MXN 7,500.00</td></tr>
 *   <tr><td>MXN 8.00</td><td>1,875</td><td>MXN 11,718.75</td><td>−MXN 3,281.25</td></tr>
 *   <tr><td>MXN 6.25</td><td>2,400</td><td>MXN 15,000.00</td><td>MXN 0.00</td></tr>
 * </table>
 *
 * <h2>Why each participant is their own employer, and why the dealing lag moves the date</h2>
 *
 * <p>{@link dev.fondaria.rules.DealingDateRule} floors a dealing date at the business date the
 * batch staged on — a batch is never back-dated to a price already struck (Task 21.1) — so no value
 * date can put two of these contributions on two different dates. The plan's {@code
 * dealingLagBusinessDays} can, and it is versioned per organization, so four dealing dates means
 * four plan versions and therefore four employers. Nothing in this criterion is about an employer;
 * what the four share, and all they need to share, is the fund and its price series.
 *
 * <p>The prices are written straight to {@code reference.fund_nav}, as {@link Scenario} has always
 * done, rather than delivered as provider files the way {@link NavPublicationEndToEndTest} does.
 * Three of these four dealing dates are in the <i>future</i>, and a fund cannot be valued on a date
 * that has not happened — the ingestion path is right to refuse them, so a series spanning future
 * dealing dates can only be seeded behind it.
 */
@Tag("integration")
class VolatileNavSeriesEndToEndTest {

  /** MXN 10,000.00 employee and MXN 5,000.00 employer, at the plan's own 1:2 matching ratio. */
  private static final long EMPLOYEE_CENTS = 1_000_000L;

  private static final long EMPLOYER_CENTS = 500_000L;
  private static final long TOTAL_CENTS = EMPLOYEE_CENTS + EMPLOYER_CENTS;

  /** The series, one price per dealing date: up 25%, down 36%, down 22%. */
  private static final long[] SERIES_SCALED = {
    10 * Units.SCALE, 1_250_000_000L, 800_000_000L, 625_000_000L
  };

  /** What each participant's MXN 15,000.00 buys at that date's price. Exact at every price. */
  private static final long[] EXPECTED_UNITS_SCALED = {
    1_500 * Units.SCALE, 1_200 * Units.SCALE, 1_875 * Units.SCALE, 2_400 * Units.SCALE
  };

  private static PlatformStack stack;
  private static LedgerView ledger;
  private static QueryApi query;
  private static FundId fundId;
  private static List<Scenario> joiners;

  @BeforeAll
  static void fourJoinersAcrossAFallingSeries() {
    stack = PlatformStack.getInstance();
    ledger = new LedgerView(stack);
    query = new QueryApi(stack);

    Scenario.TargetFund fund = Scenario.TargetFund.fresh(stack);
    fundId = fund.fundId();

    joiners = new ArrayList<>(SERIES_SCALED.length);
    for (int date = 0; date < SERIES_SCALED.length; date++) {
      // The lag is the date: lag 0 deals today, lag 1 on the next business date, and so on.
      joiners.add(
          Scenario.contribute(
              stack, fund, date, EMPLOYEE_CENTS, EMPLOYER_CENTS, SERIES_SCALED[date]));
    }

    // The last date carries the last price, so the live NAV a balance is served at is MXN 6.25.
    Scenario.refreshPrices(stack);
  }

  @Test
  @DisplayName("the series this suite prices against genuinely falls, and does not only rise")
  void theSeriesIncludesFallingPrices() {
    long falls = 0;
    for (int i = 1; i < SERIES_SCALED.length; i++) {
      if (SERIES_SCALED[i] < SERIES_SCALED[i - 1]) {
        falls++;
      }
    }
    assertThat(falls)
        .as(
            "a suite that only exercises rising prices passes on a broken implementation"
                + " (AGENTS.md §4); this one must keep falling prices in it")
        .isGreaterThanOrEqualTo(2);
    assertThat(SERIES_SCALED[SERIES_SCALED.length - 1])
        .as("and must end below where it started, or no participant is ever under water")
        .isLessThan(SERIES_SCALED[0]);
  }

  @Test
  @DisplayName(
      "the same money buys correctly different unit counts on different dates — more units as the"
          + " price falls, fewer as it rises")
  void identicalContributionsBuyDifferentUnitsAtDifferentPrices() {
    assertThat(joiners.stream().map(Scenario::dealingDate).distinct().toList())
        .as("four joiners, four dealing dates — otherwise they all bought at one price")
        .hasSize(joiners.size());

    for (int date = 0; date < joiners.size(); date++) {
      Scenario joiner = joiners.get(date);
      assertThat(ledger.unitsHeld(joiner))
          .as("MXN 15,000.00 at %s scaled bought a different number of units", SERIES_SCALED[date])
          .isEqualTo(Units.of(EXPECTED_UNITS_SCALED[date]));
      assertThat(ledger.vestedUnits(joiner).scaled() + ledger.unvestedUnits(joiner).scaled())
          .as("the two lots are never merged (ADR-0004) and both were priced the same way")
          .isEqualTo(EXPECTED_UNITS_SCALED[date]);
    }

    assertThat(ledger.unitsHeld(joiners.get(1)).scaled())
        .as("a 25% higher price buys 20% fewer units for the same money")
        .isLessThan(ledger.unitsHeld(joiners.get(0)).scaled());
    assertThat(ledger.unitsHeld(joiners.get(3)).scaled())
        .as("and the cheapest date buys the most")
        .isGreaterThan(ledger.unitsHeld(joiners.get(2)).scaled());

    long issued = 0;
    for (Scenario joiner : joiners) {
      issued += ledger.unitsHeld(joiner).scaled();
    }
    assertThat(ledger.unitsInIssue(fundId))
        .as("UNITS_IN_ISSUE is exactly what this fund's four holders hold between them")
        .isEqualTo(Units.of(issued));
    assertThat(ledger.fundCash(fundId))
        .as("four identical wires, all of them in the pool")
        .isEqualTo(Money.of(4 * TOTAL_CENTS, CurrencyCode.MXN));
  }

  @Test
  @DisplayName(
      "a later joiner receives none of an earlier joiner's gain: on the day the second joins, the"
          + " first is up 25% and the second is worth exactly what they paid")
  void aLaterJoinerReceivesNoneOfAnEarlierJoinersGain() {
    Nav priceWhenTheSecondJoined = Nav.of(SERIES_SCALED[1], CurrencyCode.MXN);
    Scenario first = joiners.get(0);
    Scenario second = joiners.get(1);

    Money firstValue = Money.value(ledger.unitsHeld(first), priceWhenTheSecondJoined);
    Money secondValue = Money.value(ledger.unitsHeld(second), priceWhenTheSecondJoined);

    assertThat(secondValue)
        .as(
            "the second joiner bought in at today's price, so on today's date their value is"
                + " their contribution — to the centavo, with none of the 25% the pool had"
                + " already gained")
        .isEqualTo(Money.of(TOTAL_CENTS, CurrencyCode.MXN));
    assertThat(firstValue.minus(Money.of(TOTAL_CENTS, CurrencyCode.MXN)))
        .as("while the first joiner, holding the same money's worth of cheaper units, is up 25%")
        .isEqualTo(Money.of(375_000L, CurrencyCode.MXN));
    assertThat(firstValue.cents()).isGreaterThan(secondValue.cents());

    // Nobody calculated a return for either of them: one price, one multiplication each.
    assertThat(ledger.unitsHeld(first)).isNotEqualTo(ledger.unitsHeld(second));
  }

  @Test
  @DisplayName(
      "at the series' lowest price every joiner but the last is under water, and the gain is"
          + " reported negative rather than clamped at what was paid in")
  void aFallenValueIsANegativeGainAndIsNeverClamped() {
    Nav lastPrice = Nav.of(SERIES_SCALED[SERIES_SCALED.length - 1], CurrencyCode.MXN);
    Money contributed = Money.of(TOTAL_CENTS, CurrencyCode.MXN);

    long[] expectedGainCents = {-562_500L, -750_000L, -328_125L, 0L};
    for (int date = 0; date < joiners.size(); date++) {
      Money gain = Money.value(ledger.unitsHeld(joiners.get(date)), lastPrice).minus(contributed);
      assertThat(gain)
          .as(
              "the joiner who bought at %s scaled, valued at the series' last price",
              SERIES_SCALED[date])
          .isEqualTo(Money.of(expectedGainCents[date], CurrencyCode.MXN));
      assertThat(gain.isNegative())
          .as("everyone who bought above the last price is under water, and says so")
          .isEqualTo(SERIES_SCALED[date] > lastPrice.scaled());
    }

    // ---- And the same loss on the surface a participant actually reads it from.
    Scenario first = joiners.get(0);
    Convergence.awaitScenarioIsQueryable(query, first);
    QueryApi.Response balance = query.balance(first.participantId());
    assertThat(balance.status()).isEqualTo(200);
    assertThat(balance.asLong("investedValueCents"))
        .as("1,500 units at the live price of MXN 6.25 — the units never moved, the price did")
        .isEqualTo(937_500L);
    assertThat(balance.asLong("investedValueCents") - TOTAL_CENTS)
        .as("MXN 9,375.00 held against MXN 15,000.00 contributed, signed and unclamped")
        .isEqualTo(-562_500L)
        .isNegative();
  }
}
