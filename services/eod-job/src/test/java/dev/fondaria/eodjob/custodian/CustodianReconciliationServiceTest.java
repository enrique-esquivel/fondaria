package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.CustodianReconciliationProperties;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Custodian reconciliation (Task 7.3), against the ledger and a directory of directories in memory
 * — the unit-level counterpart of {@code ReconciliationServiceTest} (Task 7.2).
 *
 * <p>Every scenario here is driven by data a real provider statement carries (a settled position
 * and a pending-trade figure), never by inspecting ledger transfer history — see {@code
 * CustodianReconciliationService}'s class Javadoc for why.
 */
class CustodianReconciliationServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.of());
  // A Tuesday, deliberately: DAY_1.plusDays(3) below must land on a business date without this
  // test having to consult the calendar to know that. Friday + 3 crosses a weekend and, while
  // BusinessCalendar rolls it forward to the following Monday (still 3 calendar days later, so it
  // happened to work), that is a coincidence a smaller shift in the constant would break —
  // adversarial review finding F.
  private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 8);
  private static final Instant DAY_1_MORNING = atNoonMexicoCity(DAY_1);

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final FundId fundId = new FundId(UUID.randomUUID());
  private final long unitLedgerId = 1_001L;

  private final FakeCustodianLedger ledger = new FakeCustodianLedger();
  private final InMemoryCustodianDirectories.ProviderAccounts providerAccounts =
      new InMemoryCustodianDirectories.ProviderAccounts();
  private final InMemoryCustodianDirectories.Statements statements =
      new InMemoryCustodianDirectories.Statements();
  private final InMemoryCustodianDirectories.Holdings holdings =
      new InMemoryCustodianDirectories.Holdings();
  private final InMemoryCustodianDirectories.Breaks breaks =
      new InMemoryCustodianDirectories.Breaks();
  private final InMemoryCustodianDirectories.Runs runs = new InMemoryCustodianDirectories.Runs();
  private final CustodianReconciliationProperties properties =
      new CustodianReconciliationProperties(Duration.ofHours(1), 3, 5);

  private static Instant atNoonMexicoCity(LocalDate date) {
    return date.atTime(LocalTime.NOON).atZone(ZoneId.of("America/Mexico_City")).toInstant();
  }

  private CustodianReconciliationService serviceAt(Instant now) {
    return new CustodianReconciliationService(
        ledger,
        accountIds,
        providerAccounts,
        statements,
        holdings,
        breaks,
        runs,
        properties,
        CALENDAR,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private AccountId unitsInIssue() {
    return accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.value().toString());
  }

  private AccountId fundCash() {
    return accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fundId.value().toString());
  }

  private UUID givenAllParticipantsAccount() {
    UUID id = UUID.randomUUID();
    providerAccounts.with(
        ProviderAccount.allParticipants(
            id, fundId, "PROV-OMNIBUS-1", DAY_1.minusYears(1), 1, DAY_1_MORNING));
    return id;
  }

  @Test
  @DisplayName("no statement for an active account: missing, and the run is blocked")
  void missingStatementBlocks() {
    UUID accountId = givenAllParticipantsAccount();
    ledger.putUnits(unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 0);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 0);

    CustodianReconciliationResult result = serviceAt(DAY_1_MORNING).reconcile();

    assertThat(result.passed()).isFalse();
    assertThat(result.run().missingStatementCount()).isEqualTo(1);
    assertThat(result.run().status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.ties()).isEmpty();

    CustodianReconciliationGate gate = new CustodianReconciliationGate(runs);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate.requirePassed(DAY_1))
        .isInstanceOf(CustodianCloseBlockedException.class)
        .hasMessageContaining("1 missing statement(s)");
  }

  @Test
  @DisplayName("a break is detected, quantified exactly, and ages across passes until it escalates")
  void aRealBreakIsDetectedQuantifiedAndAges() {
    UUID accountId = givenAllParticipantsAccount();
    ledger.putUnits(
        unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 1_000 * Units.SCALE);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 500_00);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            DAY_1,
            990 * Units.SCALE,
            500_00,
            0,
            0,
            "provider-file-day1.csv",
            "sha256-day1",
            DAY_1_MORNING));

    CustodianReconciliationResult day1 = serviceAt(DAY_1_MORNING).reconcile();

    assertThat(day1.passed()).isFalse();
    assertThat(day1.run().realBreakCount()).isEqualTo(1);
    assertThat(day1.run().timingDifferenceCount()).isEqualTo(0);
    Optional<CustodianBreak> opened = breaks.findOpen(accountId, BreakCategory.UNIT_BREAK);
    assertThat(opened).isPresent();
    assertThat(opened.get().driftScaled())
        .as("1,000 in the ledger minus 990 reported, exactly, at 1e-8 scale")
        .isEqualTo(10 * Units.SCALE);
    assertThat(opened.get().status()).isEqualTo(BreakStatus.OPEN);
    assertThat(opened.get().ageInDays(DAY_1)).isZero();

    // Same drift persists for three more calendar days -- the real-break threshold, which ages on
    // calendar days (custodian_break_open_by_age, V27), not business days.
    LocalDate day4 = DAY_1.plusDays(3);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            day4,
            990 * Units.SCALE,
            500_00,
            0,
            0,
            "provider-file-day4.csv",
            "sha256-day4",
            atNoonMexicoCity(day4)));
    CustodianReconciliationResult day4Result = serviceAt(atNoonMexicoCity(day4)).reconcile();

    assertThat(day4Result.passed()).isFalse();
    Optional<CustodianBreak> stillOpen = breaks.findOpen(accountId, BreakCategory.UNIT_BREAK);
    assertThat(stillOpen).isPresent();
    assertThat(stillOpen.get().status())
        .as("aged three business days, exactly the configured real-break threshold")
        .isEqualTo(BreakStatus.ESCALATED);
    assertThat(stillOpen.get().ageInDays(day4)).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "a drift the statement's own pending-trade figure explains is a timing difference, not a break")
  void aTimingDifferenceIsClassifiedDistinctly() {
    UUID accountId = givenAllParticipantsAccount();
    ledger.putUnits(
        unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 1_000 * Units.SCALE);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 500_00);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            DAY_1,
            990 * Units.SCALE,
            500_00,
            10 * Units.SCALE, // exactly explains the unit drift: a trade in flight
            0,
            "provider-file.csv",
            "sha256",
            DAY_1_MORNING));

    CustodianReconciliationResult result = serviceAt(DAY_1_MORNING).reconcile();

    assertThat(result.passed())
        .as("a timing difference is expected and does not block the gate")
        .isTrue();
    assertThat(result.run().realBreakCount()).isZero();
    assertThat(result.run().timingDifferenceCount()).isEqualTo(1);
    assertThat(breaks.findOpen(accountId, BreakCategory.UNIT_BREAK)).isEmpty();
    Optional<CustodianBreak> timing =
        breaks.findOpen(accountId, BreakCategory.UNIT_TIMING_DIFFERENCE);
    assertThat(timing).isPresent();
    assertThat(timing.get().driftScaled()).isEqualTo(10 * Units.SCALE);
  }

  @Test
  @DisplayName("no code path adjusts the ledger to match the custodian, however large the break")
  void neverAdjustsTheLedger() {
    UUID accountId = givenAllParticipantsAccount();
    ledger.putUnits(
        unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 1_000 * Units.SCALE);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 500_00);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            DAY_1,
            1 * Units.SCALE, // enormous, unexplained drift
            1,
            0,
            0,
            "provider-file.csv",
            "sha256",
            DAY_1_MORNING));

    // FakeCustodianLedger throws AssertionError from every write method (createAccount,
    // createTransfer, createTransfers, createLinkedChain). This only passes because reconcile()
    // never calls any of them, however far apart the two sides are.
    assertThatCode(() -> serviceAt(DAY_1_MORNING).reconcile()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "full cycle reconciles to zero, and the tracked timing difference resolves, once the trade settles")
  void fullCycleResolvesAfterTradesSettle() {
    UUID accountId = givenAllParticipantsAccount();
    ledger.putUnits(
        unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 1_000 * Units.SCALE);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 500_00);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            DAY_1,
            990 * Units.SCALE,
            500_00,
            10 * Units.SCALE,
            0,
            "provider-file-day1.csv",
            "sha256-day1",
            DAY_1_MORNING));

    CustodianReconciliationResult day1 = serviceAt(DAY_1_MORNING).reconcile();
    assertThat(day1.passed()).isTrue();
    assertThat(breaks.findOpen(accountId, BreakCategory.UNIT_TIMING_DIFFERENCE)).isPresent();

    LocalDate day2 = CALENDAR.nextBusinessDate(DAY_1);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            accountId,
            day2,
            1_000 * Units.SCALE, // the custodian has now caught up
            500_00,
            0,
            0,
            "provider-file-day2.csv",
            "sha256-day2",
            atNoonMexicoCity(day2)));
    CustodianReconciliationResult day2Result = serviceAt(atNoonMexicoCity(day2)).reconcile();

    assertThat(day2Result.passed()).isTrue();
    assertThat(day2Result.run().timingDifferenceCount()).isZero();
    Optional<CustodianBreak> resolved =
        breaks.findOpen(accountId, BreakCategory.UNIT_TIMING_DIFFERENCE);
    assertThat(resolved)
        .as("resolved automatically because the drift cleared -- not because units were adjusted")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "an ORGANIZATION-scoped account reconciles its units independently of the ALL_PARTICIPANTS"
          + " account for the same fund, with no change to production code")
  void organizationScopeReconcilesIndependently() {
    UUID omnibusId = givenAllParticipantsAccount();
    ledger.putUnits(
        unitsInIssue(), LedgerCodes.AccountCode.UNITS_IN_ISSUE, unitLedgerId, 1_000 * Units.SCALE);
    ledger.putCash(fundCash(), LedgerCodes.AccountCode.FUND_CASH, 500_00);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            omnibusId,
            DAY_1,
            1_000 * Units.SCALE,
            500_00,
            0,
            0,
            "omnibus-file.csv",
            "sha256-omnibus",
            DAY_1_MORNING));

    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    UUID organizationAccountId = UUID.randomUUID();
    providerAccounts.with(
        ProviderAccount.organization(
            organizationAccountId,
            fundId,
            organizationId,
            "PROV-SEGREGATED-1",
            DAY_1.minusYears(1),
            1,
            DAY_1_MORNING));
    HoldingKey holdingKey = new HoldingKey(organizationId, "employee-0001", fundId);
    holdings.with(holdingKey);
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, holdingKey.scope()),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        unitLedgerId,
        300 * Units.SCALE);
    ledger.putUnits(
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, holdingKey.scope()),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
        unitLedgerId,
        0);
    statements.with(
        new CustodianStatement(
            UUID.randomUUID(),
            organizationAccountId,
            DAY_1,
            300 * Units.SCALE, // ties exactly against this organization's holding alone
            500_00, // cash always compares at fund level (ADR-0019), same fund, same balance
            0,
            0,
            "segregated-file.csv",
            "sha256-segregated",
            DAY_1_MORNING));

    CustodianReconciliationResult result = serviceAt(DAY_1_MORNING).reconcile();

    assertThat(result.passed()).isTrue();
    assertThat(result.run().providerAccountCount()).isEqualTo(2);
    assertThat(result.ties()).hasSize(2);
    CustodianAccountTie omnibusTie =
        result.ties().stream()
            .filter(t -> t.providerAccountId().equals(omnibusId))
            .findFirst()
            .orElseThrow();
    CustodianAccountTie organizationTie =
        result.ties().stream()
            .filter(t -> t.providerAccountId().equals(organizationAccountId))
            .findFirst()
            .orElseThrow();
    assertThat(omnibusTie.ledgerUnitsScaled()).isEqualTo(1_000 * Units.SCALE);
    assertThat(organizationTie.ledgerUnitsScaled())
        .as("summed from this organization's holdings alone, not the fund's units_in_issue")
        .isEqualTo(300 * Units.SCALE);
    assertThat(organizationTie.unitDrift()).isZero();
    assertThat(breaks.hasOpenRealBreak()).isFalse();
  }
}
