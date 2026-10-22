package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuationapi.correction.AffectedAllotment;
import dev.fondaria.valuationapi.correction.AffectedAllotmentRepository;
import dev.fondaria.valuationapi.correction.NavCorrectionService;
import dev.fondaria.valuationapi.ingestion.ValuationBatch;
import dev.fondaria.valuationapi.strike.NavStrikeService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Task 20.7's first three criteria: <b>valuation file → validated → NAV struck → published →
 * visible in every service's cache</b>, a falling and volatile series across thirty business dates,
 * and a correction mid-series that identifies the allotments it affects.
 *
 * <p>ADR-0016 named the NAV strike as the one hop {@code libs/e2e-tests} still faked — a price
 * reached the platform as a {@code reference.fund_nav} row a test wrote. Nothing in this class
 * writes that table. A price exists here only because a provider's file was uploaded to object
 * storage, submitted by reference, checksummed, parsed, validated against the fund registry and the
 * business calendar, divided by units in issue read from TigerBeetle, and published — and the
 * allotment that priced this participant's units used the price that came out of it.
 *
 * <h2>Why the series is delivered after the contribution, and dated before it</h2>
 *
 * <p>Two rules of the platform meet here. A batch is never back-dated, so it deals on the current
 * business date at the earliest ({@code DealingDateRule}); and a fund cannot be valued on a date
 * that has not happened, so no valuation file may carry a later one. Every date this class can
 * price is therefore at or before the dealing date. Units in issue, meanwhile, appear only when the
 * allotment runs.
 *
 * <p>So the order is: contribute (the fund's first price is struck at its declared opening price,
 * because the pool holds nothing yet and there are no units to divide by), allot (450 units enter
 * issue), then deliver the thirty preceding business dates. {@code NavStrikeService} reads units in
 * issue at strike time, so those thirty strikes divide a genuinely volatile reported balance by a
 * real, non-zero ledger figure — which is the arithmetic the criterion is about. What the ordering
 * costs is that the series does not move the fund's <i>current</i> price; the dealing date is still
 * the latest date it has, and {@code FallenValueEndToEndTest} already owns the query-side effect of
 * a price that falls.
 *
 * <p><b>"Every service's cache" is two services</b>, and that was checked rather than assumed:
 * {@code valuation-api} and {@code balance-api} are the only ones holding a {@code NavCache} — the
 * first because it strikes prices, the second because it values holdings. Both are asserted here,
 * in their own Spring contexts, on the same {@code navVersionId}.
 *
 * <p><b>Every assertion is over ids this class created</b> (docs/ENGINEERING-RULES.md §4): its own
 * organizations, its own funds on their own unit ledgers, its own participants. Nothing counts rows
 * in {@code reference.fund_nav}, {@code workflow.valuation} or {@code workflow.allotment}, all of
 * which hold whatever earlier runs left behind.
 */
@Tag("integration")
class NavPublicationEndToEndTest {

  /**
   * MXN 4,500.00 wired: 300 units for the employee, 150 unvested for the employer, at MXN 10.00.
   */
  private static final long EMPLOYEE_CENTS = 300_000L;

  private static final long EMPLOYER_CENTS = 150_000L;

  /** 450 units, at scale 1e-8 — what the whole fund has in issue once the batch is allotted. */
  private static final long UNITS_IN_ISSUE_SCALED = 450L * Units.SCALE;

  /**
   * The pool before its first allotment: nothing, because nothing has been bought yet. Delivered as
   * a real row all the same, so the strike has an accepted valuation to work from — and the price
   * it produces is the fund's declared opening price, not a division by zero (Task 20.2).
   */
  private static final long EMPTY_POOL_CENTS = 0L;

  /**
   * Thirty business dates of MXN price per unit, in cents, oldest first — rising to MXN 11.50, then
   * losing nearly half over eleven dates, then recovering unevenly.
   *
   * <p>A suite that only ever raises the price passes on a broken implementation
   * (docs/ENGINEERING-RULES.md §4). The trough is MXN 5.90, below the fund's MXN 10.00 opening
   * price, so a floor at the opening price — the plausible way to get this wrong — could not
   * survive this series.
   */
  private static final List<Long> NAV_SERIES_CENTS =
      List.of(
          1000L, 1035L, 1012L, 1080L, 1104L, 1052L, 998L, 940L, 1005L, 1060L, 1128L, 1090L, 1150L,
          1042L, 900L, 812L, 740L, 620L, 655L, 590L, 648L, 712L, 690L, 775L, 830L, 792L, 861L, 900L,
          875L, 940L);

  /** The date in the middle of the series a correction targets. */
  private static final int CORRECTED_INDEX = 15;

  /** MXN 7.95, restated down from the MXN 8.12 originally struck. */
  private static final long CORRECTED_NAV_CENTS = 795L;

  private static PlatformStack stack;
  private static QueryApi query;
  private static ValuationDelivery valuations;

  private static Scenario scenario;
  private static ValuationBatch dealingDateFile;
  private static FundNav dealingDateNav;
  private static List<LocalDate> seriesDates;
  private static List<FundNav> seriesNavs;

  @BeforeAll
  static void priceAFundThroughTheRealChain() {
    stack = PlatformStack.getInstance();
    query = new QueryApi(stack);
    valuations = new ValuationDelivery(stack);

    AtomicReference<ValuationBatch> file = new AtomicReference<>();
    AtomicReference<FundNav> struck = new AtomicReference<>();
    scenario =
        Scenario.contribute(
            stack,
            EMPLOYEE_CENTS,
            EMPLOYER_CENTS,
            Scenario.TEN_PESOS_PER_UNIT,
            (aStack, fundId, dealingDate) -> {
              file.set(
                  valuations.deliverAccepted(
                      List.of(new ValuationDelivery.Row(fundId, dealingDate, EMPTY_POOL_CENTS))));
              struck.set(strike(fundId, dealingDate));
            });
    dealingDateFile = file.get();
    dealingDateNav = struck.get();

    seriesDates = businessDatesBefore(scenario.dealingDate(), NAV_SERIES_CENTS.size());
    valuations.deliverDaily(scenario.fundId(), seriesDates, reportedBalances());
    seriesNavs = new ArrayList<>(seriesDates.size());
    for (LocalDate date : seriesDates) {
      seriesNavs.add(strike(scenario.fundId(), date));
    }

    // Both readers pick the fund up on their own sweep in production; here the sweeps are an
    // hour apart and driven explicitly, so a figure about money never depends on a timer.
    Scenario.refreshPrices(stack);
    Convergence.awaitScenarioIsQueryable(query, scenario);
  }

  @Test
  @DisplayName(
      "a provider's file becomes a struck price, published into both services' caches, and the"
          + " price the participant's balance is valued at")
  void aValuationFileBecomesTheParticipantsPrice() {
    // ---- Received and validated: the file itself, not a row somebody wrote.
    assertThat(dealingDateFile.acceptedValuationCount())
        .as("the one row in the provider's file was recorded")
        .hasValue(1);

    // ---- Struck: from that valuation, against units read from the ledger.
    assertThat(dealingDateNav.valuationDate()).isEqualTo(scenario.dealingDate());
    assertThat(dealingDateNav.source()).isEqualTo("MODEL_B");
    assertThat(dealingDateNav.supersedesId()).as("an original strike, not a correction").isNull();
    assertThat(dealingDateNav.unitsInIssue().scaled())
        .as("nothing had been allotted from this fund when its first price was struck")
        .isZero();
    assertThat(dealingDateNav.nav().scaled())
        .as(
            "a fund with no units in issue strikes at its declared opening price, and the MXN 0.00"
                + " the provider reported is not divided by anything (Task 20.2)")
        .isEqualTo(Scenario.TEN_PESOS_PER_UNIT);

    // ---- Published: held by the instance that struck it, and by the one that only reads.
    assertThat(cachedBy(stack.valuationApiBean(InMemoryNavCache.class)))
        .as("valuation-api holds the price it struck")
        .isEqualTo(dealingDateNav.navVersionId());
    InMemoryNavCache reader = stack.balanceApiBean(InMemoryNavCache.class);
    assertThat(cachedBy(reader))
        .as("balance-api converged on it, in a different Spring context")
        .isEqualTo(dealingDateNav.navVersionId());
    assertThat(reader.currentAll().stream().map(CachedNav::fundId))
        .as(
            "present in the whole-cache view, which does not read through — so this is the sweep"
                + " having converged, not a miss falling back to the store")
        .contains(scenario.fundId());

    // ---- Visible: the price on the wire, on the participant's own balance.
    QueryApi.Response balance = query.balance(scenario.participantId());
    assertThat(balance.status()).isEqualTo(200);
    JsonNode nav = positionOf(balance).get("nav");
    assertThat(nav.get("navVersionId").asLong())
        .as(
            "the balance names the fund_nav row the provider's file produced (docs/ENGINEERING-RULES.md §4)")
        .isEqualTo(dealingDateNav.navVersionId());
    assertThat(nav.get("navScaled").asLong()).isEqualTo(Scenario.TEN_PESOS_PER_UNIT);
    assertThat(nav.get("stale").asBoolean()).as("struck moments ago").isFalse();
    assertThat(balance.asLong("investedValueCents"))
        .as("450 units at the price that came out of the chain")
        .isEqualTo(scenario.totalCents());
  }

  @Test
  @DisplayName(
      "thirty business dates of reported balances become thirty prices that genuinely fall, none of"
          + " them clamped")
  void aFallingAndVolatileSeriesIsStruckExactly() {
    assertThat(seriesNavs).hasSize(NAV_SERIES_CENTS.size());

    for (int i = 0; i < seriesNavs.size(); i++) {
      FundNav struck = seriesNavs.get(i);
      assertThat(struck.valuationDate()).isEqualTo(seriesDates.get(i));
      assertThat(struck.unitsInIssue().scaled())
          .as("every strike divided by the units the ledger actually holds")
          .isEqualTo(UNITS_IN_ISSUE_SCALED);
      assertThat(struck.nav().scaled())
          .as(
              "MXN %s per unit on %s, to the last digit",
              NAV_SERIES_CENTS.get(i), struck.valuationDate())
          .isEqualTo(navScaledOf(NAV_SERIES_CENTS.get(i)));
      assertThat(fundNavs().findOriginal(scenario.fundId(), seriesDates.get(i)).orElseThrow())
          .as("and it is what the store holds, not only what the service returned")
          .isEqualTo(struck);
    }

    List<Long> struckCents =
        seriesNavs.stream().map(nav -> nav.nav().scaled() / 1_000_000L).toList();
    assertThat(struckCents)
        .as("the series genuinely falls: the trough is far below where it started")
        .contains(590L);
    assertThat(struckCents.stream().min(Long::compare).orElseThrow())
        .as("and below the fund's MXN 10.00 opening price, so nothing floored it there")
        .isLessThan(Scenario.TEN_PESOS_PER_UNIT / 1_000_000L);
    assertThat(fallingSteps(struckCents))
        .as("volatile, not monotonic: prices went down on many of these dates and up on others")
        .isGreaterThan(5);
    assertThat(struckCents.get(struckCents.size() - 1))
        .as("and it ends part-way through a recovery, neither at the top nor at the bottom")
        .isBetween(struckCents.stream().min(Long::compare).orElseThrow() + 1, 1149L);
  }

  @Test
  @DisplayName(
      "a correction mid-series supersedes only its own date, leaves the original intact, and does"
          + " not move the fund's current price")
  void aCorrectionMidSeriesSupersedesOnlyItsOwnDate() {
    LocalDate corrected = seriesDates.get(CORRECTED_INDEX);
    FundNav original = seriesNavs.get(CORRECTED_INDEX);

    FundNav correction =
        stack
            .valuationApiBean(NavCorrectionService.class)
            .correct(
                scenario.fundId(),
                corrected,
                Nav.of(navScaledOf(CORRECTED_NAV_CENTS), original.nav().currency()),
                "Custodian restated the pooled balance for this date");

    assertThat(correction.supersedesId()).isEqualTo(original.navVersionId());
    assertThat(correction.reason()).isNotBlank();
    assertThat(correction.nav().scaled()).isEqualTo(navScaledOf(CORRECTED_NAV_CENTS));
    assertThat(correction.unitsInIssue())
        .as("units in issue are carried forward, never re-read: only the price was wrong")
        .isEqualTo(original.unitsInIssue());

    assertThat(fundNavs().findOriginal(scenario.fundId(), corrected).orElseThrow())
        .as("the wrong price is still there, readable and unchanged — never deleted or edited")
        .isEqualTo(original);
    assertThat(fundNavs().findLive(scenario.fundId(), corrected).orElseThrow())
        .as("and the live price for that date is now the correction")
        .isEqualTo(correction);

    assertThat(fundNavs().findLive(scenario.fundId(), seriesDates.get(CORRECTED_INDEX - 1)))
        .as("the date before is untouched")
        .contains(seriesNavs.get(CORRECTED_INDEX - 1));
    assertThat(fundNavs().findLive(scenario.fundId(), seriesDates.get(CORRECTED_INDEX + 1)))
        .as("and so is the date after")
        .contains(seriesNavs.get(CORRECTED_INDEX + 1));

    stack.balanceApiBean(InMemoryNavCache.class).refreshAll();
    assertThat(cachedBy(stack.valuationApiBean(InMemoryNavCache.class)))
        .as("mid-series is not current: correcting an old date does not reprice today")
        .isEqualTo(dealingDateNav.navVersionId());
    assertThat(cachedBy(stack.balanceApiBean(InMemoryNavCache.class)))
        .isEqualTo(dealingDateNav.navVersionId());

    assertThat(affectedBy(original))
        .as("nothing was ever allotted at this price, which is not an error")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "correcting the price an allotment was struck at identifies that allotment, leaves it"
          + " unchanged, and reprices the fund")
  void aCorrectionIdentifiesTheAllotmentsItAffects() {
    // Its own employer, fund and participant: this test supersedes the price the allotment
    // used, which would change what every other test in this class reads.
    AtomicReference<FundNav> struck = new AtomicReference<>();
    Scenario affected =
        Scenario.contribute(
            stack,
            EMPLOYEE_CENTS,
            EMPLOYER_CENTS,
            Scenario.TEN_PESOS_PER_UNIT,
            (aStack, fundId, dealingDate) -> {
              valuations.deliverAccepted(
                  List.of(new ValuationDelivery.Row(fundId, dealingDate, EMPTY_POOL_CENTS)));
              struck.set(strike(fundId, dealingDate));
            });
    FundNav allottedAt = struck.get();

    List<AffectedAllotment> before = affectedBy(allottedAt);
    assertThat(before).as("both legs of the contribution were priced at it").hasSize(2);
    assertThat(before.stream().map(AffectedAllotment::units).map(Units::scaled))
        .containsExactlyInAnyOrder(affected.vestedUnitsScaled(), affected.unvestedUnitsScaled());
    assertThat(before.stream().map(AffectedAllotment::organizationId))
        .containsOnly(affected.organizationId());

    FundNav correction =
        stack
            .valuationApiBean(NavCorrectionService.class)
            .correct(
                affected.fundId(),
                affected.dealingDate(),
                Nav.of(navScaledOf(940L), allottedAt.nav().currency()),
                "Opening price misdeclared at fund registration");

    assertThat(affectedBy(allottedAt))
        .as(
            "superseding a price rewrites no allotment: the same two rows still name the superseded"
                + " version, with the same units (re-allotment is a Phase 14 correction)")
        .isEqualTo(before);
    assertThat(affectedBy(correction))
        .as("and nothing was moved onto the corrected version either")
        .isEmpty();

    stack.balanceApiBean(InMemoryNavCache.class).refreshAll();
    assertThat(cachedBy(stack.valuationApiBean(InMemoryNavCache.class), affected.fundId()))
        .as("the correction is this fund's current price now, in the instance that wrote it")
        .isEqualTo(correction.navVersionId());
    assertThat(cachedBy(stack.balanceApiBean(InMemoryNavCache.class), affected.fundId()))
        .as("and in the one that only reads")
        .isEqualTo(correction.navVersionId());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static FundNav strike(FundId fundId, LocalDate valuationDate) {
    return stack.valuationApiBean(NavStrikeService.class).strike(fundId, valuationDate);
  }

  private static FundNavRepository fundNavs() {
    return stack.valuationApiBean(FundNavRepository.class);
  }

  private static List<AffectedAllotment> affectedBy(FundNav nav) {
    return stack
        .valuationApiBean(AffectedAllotmentRepository.class)
        .findByNavVersionId(nav.navVersionId());
  }

  private static long cachedBy(InMemoryNavCache cache) {
    return cachedBy(cache, scenario.fundId());
  }

  private static long cachedBy(InMemoryNavCache cache, FundId fundId) {
    Optional<CachedNav> held = cache.current(fundId);
    assertThat(held).as("a price is held for %s", fundId).isPresent();
    return held.orElseThrow().navVersionId();
  }

  /** MXN cents per unit as a Nav at scale 1e-8. */
  private static long navScaledOf(long cents) {
    return cents * 1_000_000L;
  }

  /** The reported pooled balance that prices 450 units at each of the series' NAVs, exactly. */
  private static List<Long> reportedBalances() {
    return NAV_SERIES_CENTS.stream()
        .map(cents -> cents * (UNITS_IN_ISSUE_SCALED / Units.SCALE))
        .toList();
  }

  /** How many consecutive pairs in the series fall. Zero would mean the suite proves nothing. */
  private static int fallingSteps(List<Long> cents) {
    int falling = 0;
    for (int i = 1; i < cents.size(); i++) {
      if (cents.get(i) < cents.get(i - 1)) {
        falling++;
      }
    }
    return falling;
  }

  /** The {@code count} business dates immediately before {@code date}, oldest first. */
  private static List<LocalDate> businessDatesBefore(LocalDate date, int count) {
    BusinessCalendar calendar = stack.allotmentJobBean(BusinessCalendar.class);
    List<LocalDate> dates = new ArrayList<>(count);
    LocalDate candidate = date;
    for (int i = 0; i < count; i++) {
      candidate = candidate.minusDays(1);
      while (!calendar.isBusinessDate(candidate)) {
        candidate = candidate.minusDays(1);
      }
      dates.add(candidate);
    }
    return dates.reversed();
  }

  private static JsonNode positionOf(QueryApi.Response balance) {
    for (JsonNode position : balance.json().get("positions")) {
      if (position.get("fundId").asText().equals(scenario.fundId().toString())) {
        return position;
      }
    }
    throw new AssertionError("no position for " + scenario.fundId() + " in " + balance.body());
  }
}
