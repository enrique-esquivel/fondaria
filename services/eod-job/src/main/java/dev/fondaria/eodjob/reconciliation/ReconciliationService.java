package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.ReconciliationProperties;
import dev.fondaria.eodjob.fund.FundDirectory;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.eodjob.organization.OrganizationDirectory;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Internal reconciliation (Task 7.2): three independent derivations of what the book holds, which
 * must agree <b>exactly</b>.
 *
 * <ol>
 *   <li>{@code Σ participant units} per fund, from {@code LedgerPort}
 *   <li>{@code units_in_issue} per fund, from {@code LedgerPort}
 *   <li>{@code Σ participant_units} per fund, from the CDC-fed projection in PostgreSQL
 * </ol>
 *
 * <p>Plus cash: every ledger cash account's balance against the same account's credits minus debits
 * over {@code projection.transfer}.
 *
 * <h2>Why three, and why these three</h2>
 *
 * <p>(1) and (2) are the two ends of the same postings. Every issuance debits {@code
 * UNITS_IN_ISSUE} and credits a participant, so by construction they are equal
 * (chart-of-accounts.md §5) — and a linked chain that ever credited a participant without the
 * matching contra leg would show up here and nowhere else. (3) travels a completely different road:
 * TigerBeetle's CDC job, RabbitMQ, the bridge, Redpanda, {@code projection-worker}, PostgreSQL. It
 * breaks for reasons the first two cannot — a dropped message, a replay written to the wrong row, a
 * consumer that skipped what it could not parse. Comparing (1) against (2) proves the ledger
 * internally consistent; comparing either against (3) proves the platform's own copy of it is.
 *
 * <h2>What this class must never do</h2>
 *
 * <p><b>It never writes to the ledger.</b> Not a correcting transfer, not an account, nothing —
 * {@code LedgerConfiguration} wires no {@code TransferIdDeriver}, so it structurally cannot. A
 * reconciliation able to post is a reconciliation able to make itself pass, and phase-07 states the
 * rule for the custodian case in the same words: no code path adjusts participant units to match.
 *
 * <p><b>It never treats a small difference as noise.</b> There is no tolerance parameter. Units are
 * integers at 1e-8 and cash is integer cents; two derivations of the same integer either agree or
 * one of them is wrong.
 *
 * <h2>Live against live, not as-of</h2>
 *
 * <p>{@code LedgerPort} answers <i>now</i> and has no as-of read, so both sides are read as they
 * stand and the business date labels the close the pass belongs to. A movement landing between the
 * two reads is therefore visible as drift — which is the correct behaviour for a gate that runs
 * after cut-off, and the reason a pass is repeated rather than tuned when the book is still moving.
 */
@Service
public class ReconciliationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationService.class);

  /** Cash accounts with exactly one instance platform-wide (chart-of-accounts.md §2). */
  private static final List<LedgerCodes.AccountCode> PLATFORM_CASH_ACCOUNTS =
      List.of(
          LedgerCodes.AccountCode.BANK_CLEARING,
          LedgerCodes.AccountCode.FEE_INCOME,
          LedgerCodes.AccountCode.ISR_WITHHOLDING_PAYABLE,
          LedgerCodes.AccountCode.PAYOUT_CLEARING,
          LedgerCodes.AccountCode.ROUNDING_RESIDUE);

  /** Cash accounts with one instance per fund. */
  private static final List<LedgerCodes.AccountCode> PER_FUND_CASH_ACCOUNTS =
      List.of(
          LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE,
          LedgerCodes.AccountCode.REDEMPTION_SUSPENSE,
          LedgerCodes.AccountCode.FUND_CASH,
          LedgerCodes.AccountCode.OPENING_BALANCE_EQUITY);

  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final FundDirectory funds;
  private final OrganizationDirectory organizations;
  private final HoldingAccountSource holdings;
  private final ProjectionTotals projection;
  private final ReconciliationRepository runs;
  private final ReconciliationProperties properties;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public ReconciliationService(
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      FundDirectory funds,
      OrganizationDirectory organizations,
      HoldingAccountSource holdings,
      ProjectionTotals projection,
      ReconciliationRepository runs,
      ReconciliationProperties properties,
      BusinessCalendar calendar,
      Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.organizations = Objects.requireNonNull(organizations, "organizations must not be null");
    this.holdings = Objects.requireNonNull(holdings, "holdings must not be null");
    this.projection = Objects.requireNonNull(projection, "projection must not be null");
    this.runs = Objects.requireNonNull(runs, "runs must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Runs one pass for the business date the clock currently falls in, and records it. */
  public ReconciliationResult reconcile() {
    return reconcile(calendar.businessDateOf(clock.instant()));
  }

  /**
   * Runs one pass for {@code businessDate} and records it.
   *
   * <p>The scheduled close passes {@code CloseWindow}'s closing date, which is a past date while
   * that date is still unsigned after its cut-off. The book is still read live (see "Live against
   * live"), so a run labelled with a past date proves the book as it stands now — which still
   * contains everything that date posted, and nothing more while the next date has not started.
   *
   * <p>The run row is opened {@code RUNNING} <b>before</b> any comparison, so a process that dies
   * mid-pass leaves a row that is visibly not a pass rather than no row at all — the gate cannot
   * tell "never ran" from "crashed", and treats both as blocked, but an operator can.
   */
  public ReconciliationResult reconcile(LocalDate businessDate) {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Instant startedAt = clock.instant();
    UUID runId = UUID.randomUUID();
    runs.open(runId, businessDate, startedAt);
    try {
      return compare(runId, businessDate, startedAt);
    } catch (RuntimeException e) {
      // A pass that could not finish is not a pass. Recorded FAILED with no breaks, which is a
      // distinct and honest state: nothing was found because nothing was compared.
      Instant failedAt = clock.instant();
      runs.fail(runId, failedAt, java.time.Duration.between(startedAt, failedAt).toMillis());
      LOGGER.error(
          "internal reconciliation failed to complete: runId={} businessDate={}",
          runId,
          businessDate,
          e);
      throw e;
    }
  }

  private ReconciliationResult compare(UUID runId, LocalDate businessDate, Instant startedAt) {
    List<RegisteredFund> registeredFunds = funds.allFunds();
    List<ReconciliationBreak> breaks = new ArrayList<>();

    ParticipantUnitTotals participantUnits = sumParticipantUnits();
    Map<Long, Units> unitsInIssue = readUnitsInIssue(registeredFunds);
    Map<Long, Long> projectionUnits = projection.unitsByFundLedger();

    List<FundUnitTie> unitTies = new ArrayList<>();
    for (RegisteredFund fund : registeredFunds) {
      long ledgerId = fund.unitLedgerId();
      FundUnitTie tie =
          new FundUnitTie(
              fund.fundId(),
              ledgerId,
              Units.of(participantUnits.unitsFor(ledgerId)),
              unitsInIssue.getOrDefault(ledgerId, Units.of(0L)),
              Units.of(projectionUnits.getOrDefault(ledgerId, 0L)),
              participantUnits.accountsFor(ledgerId));
      unitTies.add(tie);
      breaks.addAll(unitBreaks(tie));
    }

    List<CashAccountTie> cashTies = compareCash(registeredFunds, breaks);

    Instant completedAt = clock.instant();
    long durationMillis = java.time.Duration.between(startedAt, completedAt).toMillis();
    RunStatus status = breaks.isEmpty() ? RunStatus.PASSED : RunStatus.FAILED;
    ReconciliationRun run =
        new ReconciliationRun(
            runId,
            businessDate,
            status,
            startedAt,
            Optional.of(completedAt),
            Optional.of(durationMillis),
            registeredFunds.size(),
            participantUnits.totalAccountsRead(),
            cashTies.size(),
            breaks.size());
    runs.complete(run, breaks);

    LOGGER.info(
        "internal reconciliation complete: runId={} businessDate={} status={} funds={}"
            + " holdingAccounts={} cashAccounts={} breaks={} durationMillis={}",
        runId,
        businessDate,
        status,
        registeredFunds.size(),
        participantUnits.totalAccountsRead(),
        cashTies.size(),
        breaks.size(),
        durationMillis);
    return new ReconciliationResult(run, unitTies, cashTies, breaks);
  }

  /**
   * Both comparisons a fund's units admit, each raised separately. Two breaks on one fund is
   * informative rather than redundant: the ledger disagreeing with itself and the projection
   * disagreeing with the ledger have different causes and different first diagnostic steps.
   */
  private static List<ReconciliationBreak> unitBreaks(FundUnitTie tie) {
    List<ReconciliationBreak> raised = new ArrayList<>(2);
    if (!tie.ledgerParticipantUnits().equals(tie.ledgerUnitsInIssue())) {
      raised.add(
          ReconciliationBreak.units(
              tie.fundId(),
              tie.unitLedgerId(),
              Derivation.LEDGER_PARTICIPANT_UNITS,
              tie.ledgerParticipantUnits().scaled(),
              Derivation.LEDGER_UNITS_IN_ISSUE,
              tie.ledgerUnitsInIssue().scaled()));
    }
    if (!tie.ledgerParticipantUnits().equals(tie.projectionUnits())) {
      raised.add(
          ReconciliationBreak.units(
              tie.fundId(),
              tie.unitLedgerId(),
              Derivation.LEDGER_PARTICIPANT_UNITS,
              tie.ledgerParticipantUnits().scaled(),
              Derivation.PROJECTION_UNITS,
              tie.projectionUnits().scaled()));
    }
    return raised;
  }

  /**
   * Derivation (1): every participant holding account the platform has ever allotted into, read
   * through {@code LedgerPort} in batches and summed by the fund ledger each account belongs to.
   *
   * <p>Both accounts per holding, vested and unvested — a {@code VESTING} transfer moves units
   * between them, so either one alone would drift by exactly the amount that had vested.
   *
   * <p>An account the ledger does not return holds nothing, and contributes nothing. That is the
   * truth: allotment opens a holding account and issues into it in one linked chain, so an account
   * absent from the ledger was never issued into.
   */
  private ParticipantUnitTotals sumParticipantUnits() {
    ParticipantUnitTotals totals = new ParticipantUnitTotals();
    List<AccountId> pending = new ArrayList<>(properties.accountLookupBatchSize());
    Optional<HoldingKey> cursor = Optional.empty();
    while (true) {
      List<HoldingKey> page = holdings.nextPage(cursor, properties.holdingPageSize());
      if (page.isEmpty()) {
        break;
      }
      for (HoldingKey key : page) {
        String scope = key.scope();
        // One id at a time, each followed by its own fullness check. Adding both of a holding's
        // accounts before checking lets the buffer reach batchSize + 1 on an odd batch size, and
        // the ledger refuses the whole request rather than trimming it — which surfaced as
        // "Too much data was sent or requested in this batch" at the default 8,189.
        offer(
            pending,
            accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
            totals);
        offer(
            pending,
            accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope),
            totals);
      }
      cursor = Optional.of(page.get(page.size() - 1));
      if (page.size() < properties.holdingPageSize()) {
        break;
      }
    }
    drain(pending, totals);
    return totals;
  }

  private void offer(List<AccountId> pending, AccountId id, ParticipantUnitTotals totals) {
    pending.add(id);
    if (pending.size() >= properties.accountLookupBatchSize()) {
      drain(pending, totals);
    }
  }

  private void drain(List<AccountId> pending, ParticipantUnitTotals totals) {
    if (pending.isEmpty()) {
      return;
    }
    totals.countAccountsRead(pending.size());
    for (LedgerAccount account : ledger.lookupAccounts(List.copyOf(pending))) {
      if (account instanceof LedgerAccount.Unit unit) {
        totals.add(unit.fundLedgerId(), unit.balance().scaled(), unit.accountCode());
      }
    }
    pending.clear();
  }

  /** Derivation (2): one account per fund, read in batches of whatever the ledger will carry. */
  private Map<Long, Units> readUnitsInIssue(List<RegisteredFund> registeredFunds) {
    Map<AccountId, Long> ledgerIdByAccount = new LinkedHashMap<>();
    for (RegisteredFund fund : registeredFunds) {
      ledgerIdByAccount.put(
          accountIds.derive(
              LedgerCodes.AccountCode.UNITS_IN_ISSUE, fund.fundId().value().toString()),
          fund.unitLedgerId());
    }
    Map<Long, Units> byLedger = new HashMap<>();
    for (LedgerAccount account : lookupInBatches(List.copyOf(ledgerIdByAccount.keySet()))) {
      if (account instanceof LedgerAccount.Unit unit) {
        byLedger.put(unit.fundLedgerId(), unit.balance());
      }
    }
    return byLedger;
  }

  /**
   * Cash: every account the platform can name, from the ledger and from the projection's own
   * movements.
   *
   * <p>Named accounts only. Every cash account id on this platform is <i>derived</i> from a fund,
   * an organization or nothing at all (chart-of-accounts.md §2), so the set of accounts that can
   * exist is exactly the set this method builds; there is no registry to fall out of step with. An
   * id appearing in the projection that this set does not contain would mean a fund or organization
   * was registered between the directory read and the projection read, and the next pass reconciles
   * it.
   */
  private List<CashAccountTie> compareCash(
      List<RegisteredFund> registeredFunds, List<ReconciliationBreak> breaks) {
    Map<AccountId, LedgerCodes.AccountCode> named = new LinkedHashMap<>();
    for (LedgerCodes.AccountCode code : PLATFORM_CASH_ACCOUNTS) {
      named.put(accountIds.derive(code), code);
    }
    for (RegisteredFund fund : registeredFunds) {
      String scope = fund.fundId().value().toString();
      for (LedgerCodes.AccountCode code : PER_FUND_CASH_ACCOUNTS) {
        named.put(accountIds.derive(code, scope), code);
      }
    }
    for (OrganizationId organizationId : organizations.allOrganizations()) {
      named.put(
          accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()),
          LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS);
    }

    Map<String, Long> projectionCash = projection.cashByAccount();
    Map<AccountId, Money> ledgerCash = new HashMap<>();
    for (LedgerAccount account : lookupInBatches(List.copyOf(named.keySet()))) {
      if (account instanceof LedgerAccount.Cash cash) {
        ledgerCash.put(cash.id(), cash.balance());
      }
    }

    List<CashAccountTie> ties = new ArrayList<>(named.size());
    for (Map.Entry<AccountId, LedgerCodes.AccountCode> entry : named.entrySet()) {
      AccountId id = entry.getKey();
      String decimal = id.asUnsignedDecimal();
      CashAccountTie tie =
          new CashAccountTie(
              id,
              entry.getValue(),
              ledgerCash.getOrDefault(id, Money.of(0L, CurrencyCode.MXN)),
              Money.of(projectionCash.getOrDefault(decimal, 0L), CurrencyCode.MXN));
      ties.add(tie);
      if (!tie.ties()) {
        breaks.add(
            ReconciliationBreak.cash(
                decimal, tie.ledgerCash().cents(), tie.projectionCash().cents()));
      }
    }
    return ties;
  }

  private List<LedgerAccount> lookupInBatches(List<AccountId> ids) {
    List<LedgerAccount> found = new ArrayList<>(ids.size());
    int batch = properties.accountLookupBatchSize();
    for (int from = 0; from < ids.size(); from += batch) {
      found.addAll(ledger.lookupAccounts(ids.subList(from, Math.min(from + batch, ids.size()))));
    }
    return found;
  }

  /** Running per-fund totals for derivation (1), kept out of the flow above for readability. */
  private static final class ParticipantUnitTotals {

    private final Map<Long, Long> unitsByLedger = new HashMap<>();
    private final Map<Long, Long> accountsByLedger = new HashMap<>();
    private long accountsRead;

    void add(long fundLedgerId, long unitsScaled, LedgerCodes.AccountCode code) {
      if (code != LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED
          && code != LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED) {
        // Nothing derives another code under a participant scope, so reaching this means the
        // derivation collided with an account it did not open. Counting it would fold a foreign
        // balance into a participant total, which is the one outcome worse than a break.
        throw new IllegalStateException(
            "a derived participant holding id resolved to a " + code + " account");
      }
      unitsByLedger.merge(fundLedgerId, unitsScaled, Math::addExact);
      accountsByLedger.merge(fundLedgerId, 1L, Math::addExact);
    }

    void countAccountsRead(int howMany) {
      accountsRead = Math.addExact(accountsRead, howMany);
    }

    long unitsFor(long fundLedgerId) {
      return unitsByLedger.getOrDefault(fundLedgerId, 0L);
    }

    long accountsFor(long fundLedgerId) {
      return accountsByLedger.getOrDefault(fundLedgerId, 0L);
    }

    long totalAccountsRead() {
      return accountsRead;
    }
  }
}
