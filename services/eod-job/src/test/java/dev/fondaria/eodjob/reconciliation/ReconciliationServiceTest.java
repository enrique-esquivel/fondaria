package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.ReconciliationProperties;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three-derivation tie-out itself (Task 7.2), with every source in memory so the arithmetic and
 * the classification of a break are visible without a container.
 *
 * <h2>Why every expected drift here is a literal</h2>
 *
 * <p>A reconciliation asserted against a difference the code under test also computed would agree
 * with itself for any implementation, including one that always answered zero. So each expectation
 * below is written out: 900 units against 899 is a drift of exactly {@code 1 * Units.SCALE}, in
 * that direction, and the test says so rather than subtracting the two figures it fed in.
 *
 * <h2>No NAV appears in this suite, deliberately</h2>
 *
 * <p>docs/ENGINEERING-RULES.md §4 requires a falling and volatile price series wherever a suite
 * exercises prices. This one exercises none: a tie-out compares <i>units</i> against units and
 * cents against cents, and a price never enters the comparison. That is the property being asserted
 * as much as anything else — a reconciliation that moved with the NAV would tie on a falling day
 * and break on a rising one. The value of those units at a price is {@link
 * dev.fondaria.common.Money#value}'s business, and the end-to-end suite prices its fund on a
 * falling series.
 */
class ReconciliationServiceTest {

  private static final UUID NAMESPACE = UUID.fromString("6f0f1b3e-2f7a-4f24-9c62-1a3d8c7b5e11");
  private static final long FUND_LEDGER = 1_001L;
  private static final long CENT = 1L;

  private static final Instant NOON =
      Instant.parse("2026-09-04T17:00:00Z"); // 11:00 in America/Mexico_City, before cut-off

  private final AccountIdDeriver accountIds = new AccountIdDeriver(NAMESPACE);
  private final FakeLedger ledger = new FakeLedger();
  private final InMemoryDirectories.Funds funds = new InMemoryDirectories.Funds();
  private final InMemoryDirectories.Organizations organizations =
      new InMemoryDirectories.Organizations();
  private final InMemoryDirectories.Holdings holdings = new InMemoryDirectories.Holdings();
  private final InMemoryDirectories.Projection projection = new InMemoryDirectories.Projection();
  private final InMemoryDirectories.Runs runs = new InMemoryDirectories.Runs();

  private final OrganizationId acme = new OrganizationId(UUID.randomUUID());
  private final FundId fund = new FundId(UUID.randomUUID());

  @Test
  @DisplayName("a book where all three derivations agree passes, and raises nothing")
  void aTiedBookPasses() {
    givenParticipants(600 * Units.SCALE, 300 * Units.SCALE);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 900 * Units.SCALE);

    ReconciliationResult result = service().reconcile();

    assertThat(result.breaks()).isEmpty();
    assertThat(result.passed()).isTrue();
    assertThat(result.run().status()).isEqualTo(RunStatus.PASSED);
    assertThat(result.tieForLedger(FUND_LEDGER).orElseThrow().ties()).isTrue();
  }

  @Test
  @DisplayName(
      "vested and unvested are both counted, so units that have vested move between accounts"
          + " without moving the total")
  void vestingMovesNothingTheTieOutCanSee() {
    // The same 900 units, differently split: what vesting does to a participant's two accounts.
    givenParticipants(900 * Units.SCALE, 0L);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 900 * Units.SCALE);

    assertThat(service().reconcile().breaks()).isEmpty();
  }

  @Test
  @DisplayName(
      "a unit the projection holds and the ledger does not is detected, and quantified exactly")
  void aProjectionAheadOfTheLedgerBreaks() {
    givenParticipants(600 * Units.SCALE, 300 * Units.SCALE);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 901 * Units.SCALE);

    ReconciliationResult result = service().reconcile();

    assertThat(result.passed()).isFalse();
    assertThat(result.breaks()).hasSize(1);
    ReconciliationBreak raised = result.breaks().get(0);
    assertThat(raised.scope()).isEqualTo(BreakScope.FUND_UNITS);
    assertThat(raised.fundId()).contains(fund);
    assertThat(raised.left()).isEqualTo(Derivation.LEDGER_PARTICIPANT_UNITS);
    assertThat(raised.right()).isEqualTo(Derivation.PROJECTION_UNITS);
    assertThat(raised.driftScaled())
        .as("the ledger holds one unit fewer than the projection claims, and the sign says so")
        .isEqualTo(-1 * Units.SCALE);
  }

  @Test
  @DisplayName("a unit the ledger holds and the projection lost drifts the other way, unclamped")
  void aProjectionBehindTheLedgerBreaks() {
    givenParticipants(600 * Units.SCALE, 300 * Units.SCALE);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 899 * Units.SCALE);

    ReconciliationBreak raised = service().reconcile().breaks().get(0);

    assertThat(raised.driftScaled()).isEqualTo(1 * Units.SCALE);
  }

  @Test
  @DisplayName("one hundred-millionth of a unit is a break: there is no tolerance")
  void thereIsNoToleranceBand() {
    givenParticipants(600 * Units.SCALE, 300 * Units.SCALE);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 900 * Units.SCALE - 1);

    ReconciliationResult result = service().reconcile();

    assertThat(result.passed()).isFalse();
    assertThat(result.breaks().get(0).driftScaled()).isEqualTo(1L);
  }

  @Test
  @DisplayName(
      "the ledger disagreeing with itself is a separate finding from the projection disagreeing"
          + " with the ledger")
  void bothComparisonsAreRaisedSeparately() {
    givenParticipants(600 * Units.SCALE, 300 * Units.SCALE);
    ledgerUnitsInIssue(880 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 870 * Units.SCALE);

    List<ReconciliationBreak> breaks = service().reconcile().breaksForLedger(FUND_LEDGER);

    assertThat(breaks).hasSize(2);
    assertThat(breaks.get(0).right()).isEqualTo(Derivation.LEDGER_UNITS_IN_ISSUE);
    assertThat(breaks.get(0).driftScaled()).isEqualTo(20 * Units.SCALE);
    assertThat(breaks.get(1).right()).isEqualTo(Derivation.PROJECTION_UNITS);
    assertThat(breaks.get(1).driftScaled()).isEqualTo(30 * Units.SCALE);
  }

  @Test
  @DisplayName("a cash account is reconciled in both directions, and may legitimately be negative")
  void cashIsReconciledSigned() {
    givenParticipants(900 * Units.SCALE, 0L);
    ledgerUnitsInIssue(900 * Units.SCALE);
    projection.withUnits(FUND_LEDGER, 900 * Units.SCALE);

    AccountId residue = accountIds.derive(LedgerCodes.AccountCode.ROUNDING_RESIDUE);
    ledger.putCash(residue, LedgerCodes.AccountCode.ROUNDING_RESIDUE, -250L);
    projection.withCash(residue.asUnsignedDecimal(), -250L);

    AccountId fundCash =
        accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fund.value().toString());
    ledger.putCash(fundCash, LedgerCodes.AccountCode.FUND_CASH, 450_000L);
    projection.withCash(fundCash.asUnsignedDecimal(), 449_999L);

    ReconciliationResult result = service().reconcile();

    List<ReconciliationBreak> cashBreaks =
        result.breaks().stream().filter(b -> b.scope() == BreakScope.CASH_ACCOUNT).toList();
    assertThat(cashBreaks).hasSize(1);
    assertThat(cashBreaks.get(0).accountId()).contains(fundCash.asUnsignedDecimal());
    assertThat(cashBreaks.get(0).driftScaled())
        .as("one centavo the ledger holds and the projection has not seen")
        .isEqualTo(CENT);
  }

  @Test
  @DisplayName("holding accounts are read in batches no larger than the ledger will carry")
  void accountsAreReadInBoundedBatches() {
    // Five participants, ten holding accounts, a batch size of four: three full batches and a
    // remainder, never ten single reads and never one request of ten.
    for (int i = 0; i < 5; i++) {
      holdings.with(new HoldingKey(acme, "P-" + i, fund));
    }
    funds.with(new RegisteredFund(fund, FUND_LEDGER));
    ledgerUnitsInIssue(0L);

    service(4).reconcile();

    assertThat(ledger.lookupBatchSizes())
        .as("participant holdings in fours, then the fund's own UNITS_IN_ISSUE and the cash set")
        .startsWith(4, 4, 2);
    assertThat(ledger.lookupBatchSizes())
        .allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(4));
  }

  @Test
  @DisplayName("an odd batch size is still never exceeded, though a holding is two accounts")
  void anOddBatchSizeIsNeverExceeded() {
    // The regression this pins: adding both of a holding's accounts before checking whether the
    // buffer is full lets it reach batchSize + 1 whenever batchSize is odd — and the ledger's own
    // default, 8,189, is odd. TigerBeetle refuses the whole request rather than trimming it, so
    // the symptom was a pass that died at scale and worked at every size a small test used.
    for (int i = 0; i < 5; i++) {
      holdings.with(new HoldingKey(acme, "P-" + i, fund));
    }
    funds.with(new RegisteredFund(fund, FUND_LEDGER));
    ledgerUnitsInIssue(0L);

    service(3).reconcile();

    assertThat(ledger.lookupBatchSizes())
        .as("ten holding accounts in threes, and nothing over three anywhere in the pass")
        .startsWith(3, 3, 3, 1);
    assertThat(ledger.lookupBatchSizes())
        .allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(3));
  }

  @Test
  @DisplayName("a pass that cannot finish is recorded FAILED, not left as if it had never run")
  void aFailedPassIsRecorded() {
    funds.with(new RegisteredFund(fund, FUND_LEDGER));
    ProjectionTotals exploding =
        new ProjectionTotals() {
          @Override
          public java.util.Map<Long, Long> unitsByFundLedger() {
            throw new IllegalStateException("the projection database is unreachable");
          }

          @Override
          public java.util.Map<String, Long> cashByAccount() {
            throw new IllegalStateException("the projection database is unreachable");
          }
        };

    ReconciliationService service =
        new ReconciliationService(
            ledger,
            accountIds,
            funds,
            organizations,
            holdings,
            exploding,
            runs,
            new ReconciliationProperties(Duration.ofHours(1), 8_189, 50_000),
            calendar(),
            Clock.fixed(NOON, ZoneId.of("UTC")));

    assertThatThrownBy(service::reconcile).isInstanceOf(IllegalStateException.class);

    ReconciliationRun recorded = runs.latestFor(LocalDate.of(2026, 9, 4)).orElseThrow();
    assertThat(recorded.status()).isEqualTo(RunStatus.FAILED);
    assertThat(recorded.opensTheGate())
        .as("a pass that compared nothing is not a pass, and the gate must stay shut")
        .isFalse();
  }

  // ---- fixture helpers ------------------------------------------------------------------

  /** One participant holding {@code vested} and {@code unvested} units in {@link #fund}. */
  private void givenParticipants(long vestedScaled, long unvestedScaled) {
    HoldingKey key = new HoldingKey(acme, "P-001", fund);
    holdings.with(key);
    organizations.with(acme);
    funds.with(new RegisteredFund(fund, FUND_LEDGER));
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, key.scope()),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        FUND_LEDGER,
        vestedScaled);
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, key.scope()),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
        FUND_LEDGER,
        unvestedScaled);
  }

  private void ledgerUnitsInIssue(long unitsScaled) {
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fund.value().toString()),
        LedgerCodes.AccountCode.UNITS_IN_ISSUE,
        FUND_LEDGER,
        unitsScaled);
  }

  private ReconciliationService service() {
    return service(8_189);
  }

  private ReconciliationService service(int accountLookupBatchSize) {
    return new ReconciliationService(
        ledger,
        accountIds,
        funds,
        organizations,
        holdings,
        projection,
        runs,
        new ReconciliationProperties(Duration.ofHours(1), accountLookupBatchSize, 50_000),
        calendar(),
        Clock.fixed(NOON, ZoneId.of("UTC")));
  }

  private static BusinessCalendar calendar() {
    return new BusinessCalendar(
        ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.<LocalDate>of());
  }
}
