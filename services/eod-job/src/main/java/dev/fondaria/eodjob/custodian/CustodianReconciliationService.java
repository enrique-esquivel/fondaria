package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.config.CustodianReconciliationProperties;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerLimits;
import dev.fondaria.ledger.LedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Custodian reconciliation (Task 7.3): every {@link ProviderAccount} active on the business date,
 * tied against its statement.
 *
 * <p>For each account, two independent comparisons — units and cash — each split into what a
 * statement's own pending-trade figure explains (a {@link BreakCategory#UNIT_TIMING_DIFFERENCE} /
 * {@link BreakCategory#CASH_TIMING_DIFFERENCE}, expected and ages on its own threshold) and what it
 * does not (a real {@link BreakCategory#UNIT_BREAK} / {@link BreakCategory#CASH_BREAK}, which
 * blocks the gate). A missing statement is not compared at all — it blocks on its own.
 *
 * <h2>What this class must never do</h2>
 *
 * <p><b>It never writes to the ledger</b> — {@code LedgerConfiguration} wires no {@code
 * TransferIdDeriver} (`ADR-0018`), so it structurally cannot post a correcting transfer. A break
 * resolves only because a later statement's own figures tie, never because this class adjusted
 * anything to make them.
 *
 * <p><b>It never treats a break as noise.</b> There is no tolerance parameter distinct from the
 * pending-trade figure the statement itself carries — that figure is the only explanation this
 * class will accept for a drift; anything left over is a break, however small.
 */
@Service
public class CustodianReconciliationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CustodianReconciliationService.class);

  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final ProviderAccountDirectory providerAccounts;
  private final CustodianStatementRepository statements;
  private final CustodianHoldingSource holdings;
  private final CustodianBreakRepository breaks;
  private final CustodianReconciliationRepository runs;
  private final CustodianReconciliationProperties properties;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public CustodianReconciliationService(
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      ProviderAccountDirectory providerAccounts,
      CustodianStatementRepository statements,
      CustodianHoldingSource holdings,
      CustodianBreakRepository breaks,
      CustodianReconciliationRepository runs,
      CustodianReconciliationProperties properties,
      BusinessCalendar calendar,
      Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.providerAccounts =
        Objects.requireNonNull(providerAccounts, "providerAccounts must not be null");
    this.statements = Objects.requireNonNull(statements, "statements must not be null");
    this.holdings = Objects.requireNonNull(holdings, "holdings must not be null");
    this.breaks = Objects.requireNonNull(breaks, "breaks must not be null");
    this.runs = Objects.requireNonNull(runs, "runs must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Runs one pass for the business date the clock currently falls in. */
  public CustodianReconciliationResult reconcile() {
    return reconcile(calendar.businessDateOf(clock.instant()));
  }

  /**
   * Runs one pass for {@code businessDate} — {@code CloseWindow}'s closing date, a past date while
   * that date is still unsigned after its cut-off — and records it, opened {@code RUNNING} before
   * any comparison.
   */
  public CustodianReconciliationResult reconcile(LocalDate businessDate) {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Instant startedAt = clock.instant();
    UUID runId = UUID.randomUUID();
    runs.open(runId, businessDate, startedAt);
    try {
      return compare(runId, businessDate, startedAt);
    } catch (RuntimeException e) {
      Instant failedAt = clock.instant();
      runs.fail(runId, failedAt, Duration.between(startedAt, failedAt).toMillis());
      LOGGER.error(
          "custodian reconciliation failed to complete: runId={} businessDate={}",
          runId,
          businessDate,
          e);
      throw e;
    }
  }

  private CustodianReconciliationResult compare(UUID runId, LocalDate businessDate, Instant now) {
    List<ProviderAccount> accounts = providerAccounts.activeAsOf(businessDate);
    List<CustodianAccountTie> ties = new ArrayList<>();
    List<CustodianBreak> touched = new ArrayList<>();
    Map<FundId, Long> fundCashCache = new HashMap<>();
    int missing = 0;

    for (ProviderAccount account : accounts) {
      Optional<CustodianStatement> statement =
          statements.findFor(account.providerAccountId(), businessDate);
      if (statement.isEmpty()) {
        missing++;
        LOGGER.warn(
            "custodian statement missing: providerAccountId={} businessDate={}",
            account.providerAccountId(),
            businessDate);
        continue;
      }

      long ledgerUnits = resolveLedgerUnits(account);
      long ledgerCash = fundCashCache.computeIfAbsent(account.fundId(), this::resolveLedgerCash);
      CustodianStatement s = statement.get();
      CustodianAccountTie tie =
          new CustodianAccountTie(
              account.providerAccountId(),
              account.fundId(),
              account.scopeType(),
              ledgerUnits,
              s.reportedUnitsScaled(),
              s.pendingTradeUnitsScaled(),
              ledgerCash,
              s.reportedCashCents(),
              s.pendingTradeCashCents());
      ties.add(tie);

      reconcileCategory(
          account.providerAccountId(),
          BreakCategory.UNIT_TIMING_DIFFERENCE,
          BreakCategory.UNIT_BREAK,
          tie.pendingTradeUnitsScaled(),
          tie.unitDrift(),
          tie.unexplainedUnitDrift(),
          businessDate,
          now,
          touched);
      reconcileCategory(
          account.providerAccountId(),
          BreakCategory.CASH_TIMING_DIFFERENCE,
          BreakCategory.CASH_BREAK,
          tie.pendingTradeCashCents(),
          tie.cashDrift(),
          tie.unexplainedCashDrift(),
          businessDate,
          now,
          touched);
    }

    int escalated =
        breaks.escalateAged(
            businessDate,
            properties.realBreakEscalationDays(),
            properties.timingDifferenceEscalationDays(),
            now);
    boolean hasOpenReal = breaks.hasOpenRealBreak();

    Instant completedAt = clock.instant();
    long durationMillis = Duration.between(now, completedAt).toMillis();
    RunStatus status = missing == 0 && !hasOpenReal ? RunStatus.PASSED : RunStatus.FAILED;
    int realBreakCount =
        (int) touched.stream().filter(b -> !b.category().isTimingDifference()).count();
    int timingDifferenceCount =
        (int) touched.stream().filter(b -> b.category().isTimingDifference()).count();

    CustodianReconciliationRun run =
        new CustodianReconciliationRun(
            runId,
            businessDate,
            status,
            now,
            Optional.of(completedAt),
            Optional.of(durationMillis),
            accounts.size(),
            accounts.size() - missing,
            missing,
            realBreakCount,
            timingDifferenceCount);
    runs.complete(run);

    LOGGER.info(
        "custodian reconciliation complete: runId={} businessDate={} status={} providerAccounts={}"
            + " missingStatements={} realBreaks={} timingDifferences={} escalated={} durationMillis={}",
        runId,
        businessDate,
        status,
        accounts.size(),
        missing,
        realBreakCount,
        timingDifferenceCount,
        escalated,
        durationMillis);

    return new CustodianReconciliationResult(run, ties, touched, escalated);
  }

  /**
   * One dimension (units or cash) of one account: the statement's pending-trade figure explains a
   * {@code timingCategory} break; anything left over is a {@code realCategory} break. Either side
   * resolves on its own if it is no longer present.
   *
   * <p><b>Guarded on {@code totalDrift == 0}.</b> A statement can report a nonzero pending-trade
   * figure even when the settled positions already tie exactly — a note about activity that has
   * nothing left for it to explain. Without this guard that produced a {@code timingCategory} break
   * naming an amount no comparison found, <i>and</i> a spurious {@code realCategory} break of the
   * opposite sign (adversarial review finding C) — two findings where there is nothing wrong at
   * all.
   */
  private void reconcileCategory(
      UUID providerAccountId,
      BreakCategory timingCategory,
      BreakCategory realCategory,
      long pendingAmount,
      long totalDrift,
      long unexplained,
      LocalDate businessDate,
      Instant now,
      List<CustodianBreak> touched) {
    if (totalDrift == 0) {
      resolveIfOpen(providerAccountId, timingCategory, businessDate, now);
      resolveIfOpen(providerAccountId, realCategory, businessDate, now);
      return;
    }

    if (pendingAmount != 0) {
      touched.add(upsertBreak(providerAccountId, timingCategory, pendingAmount, businessDate, now));
    } else {
      resolveIfOpen(providerAccountId, timingCategory, businessDate, now);
    }

    if (unexplained != 0) {
      touched.add(upsertBreak(providerAccountId, realCategory, unexplained, businessDate, now));
    } else {
      resolveIfOpen(providerAccountId, realCategory, businessDate, now);
    }
  }

  private CustodianBreak upsertBreak(
      UUID providerAccountId,
      BreakCategory category,
      long drift,
      LocalDate businessDate,
      Instant now) {
    Optional<CustodianBreak> existing = breaks.findOpen(providerAccountId, category);
    if (existing.isPresent()) {
      breaks.confirmStillOpen(existing.get().breakId(), drift, businessDate, now);
      return breaks.findOpen(providerAccountId, category).orElseThrow();
    }
    return breaks.open(providerAccountId, category, drift, businessDate, now);
  }

  private void resolveIfOpen(
      UUID providerAccountId, BreakCategory category, LocalDate businessDate, Instant now) {
    breaks
        .findOpen(providerAccountId, category)
        .ifPresent(b -> breaks.resolve(b.breakId(), now, "drift cleared on " + businessDate));
  }

  /**
   * {@link ProviderScopeType#ALL_PARTICIPANTS} resolves straight to the fund's {@code
   * units_in_issue} account — the same figure Task 7.2 reads. {@link
   * ProviderScopeType#ORGANIZATION} sums that organization's holding accounts, batched the same way
   * {@code ReconciliationService} batches — bounded by one ledger request, {@link
   * LedgerLimits#MAX_LINKED_CHAIN_LENGTH}.
   */
  private long resolveLedgerUnits(ProviderAccount account) {
    if (account.scopeType() == ProviderScopeType.ALL_PARTICIPANTS) {
      AccountId id =
          accountIds.derive(
              LedgerCodes.AccountCode.UNITS_IN_ISSUE, account.fundId().value().toString());
      return ledger
          .lookupAccount(id)
          .filter(LedgerAccount.Unit.class::isInstance)
          .map(a -> ((LedgerAccount.Unit) a).balance().scaled())
          .orElse(0L);
    }

    OrganizationId organizationId = account.organizationId().orElseThrow();
    List<HoldingKey> keys = holdings.holdingsOf(organizationId, account.fundId());
    List<AccountId> ids = new ArrayList<>(keys.size() * 2);
    for (HoldingKey key : keys) {
      ids.add(accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, key.scope()));
      ids.add(
          accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, key.scope()));
    }
    long total = 0L;
    for (int from = 0; from < ids.size(); from += LedgerLimits.MAX_LINKED_CHAIN_LENGTH) {
      List<AccountId> batch =
          ids.subList(from, Math.min(from + LedgerLimits.MAX_LINKED_CHAIN_LENGTH, ids.size()));
      for (LedgerAccount a : ledger.lookupAccounts(batch)) {
        if (a instanceof LedgerAccount.Unit unit) {
          total = Math.addExact(total, unit.balance().scaled());
        }
      }
    }
    return total;
  }

  /**
   * Always the fund's single {@code FUND_CASH} account — cash is not scoped, at any custody shape
   * (`ADR-0019`).
   */
  private long resolveLedgerCash(FundId fundId) {
    AccountId id = accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fundId.value().toString());
    return ledger
        .lookupAccount(id)
        .filter(LedgerAccount.Cash.class::isInstance)
        .map(a -> ((LedgerAccount.Cash) a).balance().cents())
        .orElse(0L);
  }
}
