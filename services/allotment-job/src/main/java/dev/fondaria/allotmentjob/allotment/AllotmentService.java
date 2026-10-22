package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.allotmentjob.batch.ContributionSource;
import dev.fondaria.allotmentjob.batch.StagedBatch;
import dev.fondaria.allotmentjob.batch.StagedRow;
import dev.fondaria.allotmentjob.fund.FundDirectory;
import dev.fondaria.allotmentjob.nav.AllotmentNavSource;
import dev.fondaria.allotmentjob.plan.PlanVersionDirectory;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerLimits;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.rules.DealingDateRule;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.PlanVersion;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The allotment run's business logic (phase-21-allotment.md, Task 21.2): prices one staged batch at
 * the NAV struck for its dealing date, and posts the {@code CONTRIBUTION_ALLOTMENT} chain
 * (chart-of-accounts.md §4) as one linked chain, fired atomically.
 *
 * <p><b>This class never strikes a NAV and never moves one.</b> It reads a price valuation-api
 * already struck ({@link AllotmentNavSource#findOriginal}) and divides by it — {@code AGENTS.md}
 * §2: "a contribution never moves the NAV. Pool value and units in issue rise proportionally; the
 * price is unchanged." Nothing here computes a per-participant return either; the one division is
 * {@link #floorUnits}, unchanged in spirit from {@code NavPerUnit#strike}.
 *
 * <p><b>Not ready is not an error.</b> A batch whose plan version has no resolvable fund, or whose
 * dealing date has no struck NAV yet, is reported via the empty {@link Optional} return — the
 * caller leaves it {@code STAGED} and tries again next sweep. Only a ledger rejection is a genuine
 * failure ({@link IllegalStateException}): every other input this method needs was already
 * validated when the batch staged (Task 3.6) or is a timing condition that resolves itself.
 */
@Service
public class AllotmentService {

  private final PlanVersionDirectory planVersions;
  private final FundDirectory funds;
  private final AllotmentNavSource navSource;
  private final BusinessCalendar calendar;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final TransferIdDeriver transferIds;
  private final TenantRefDeriver tenantRefs;
  private final AllotmentRepository allotments;
  private final AllotmentResidualRepository residuals;
  private final Clock clock;
  private final AllotmentMetrics metrics;

  public AllotmentService(
      PlanVersionDirectory planVersions,
      FundDirectory funds,
      AllotmentNavSource navSource,
      BusinessCalendar calendar,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      TransferIdDeriver transferIds,
      TenantRefDeriver tenantRefs,
      AllotmentRepository allotments,
      AllotmentResidualRepository residuals,
      Clock clock,
      AllotmentMetrics metrics) {
    this.planVersions = Objects.requireNonNull(planVersions, "planVersions must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.navSource = Objects.requireNonNull(navSource, "navSource must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.transferIds = Objects.requireNonNull(transferIds, "transferIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
    this.allotments = Objects.requireNonNull(allotments, "allotments must not be null");
    this.residuals = Objects.requireNonNull(residuals, "residuals must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
  }

  /**
   * Attempts to allot one staged batch. Empty when the batch is not ready yet (no resolvable plan
   * version, no registered fund as of its dealing date, or no NAV struck for its dealing date) —
   * the caller must leave the batch {@code STAGED}. Present when the chain was posted (or was
   * already posted by an earlier, interrupted attempt — see below); the caller may now mark the
   * batch {@code ALLOTTED}.
   *
   * @throws IllegalStateException the ledger rejected the chain — a genuine failure, since every
   *     precondition the ledger enforces (sufficient units, valid accounts) was engineered to hold
   *     by construction
   */
  public Optional<LocalDate> allot(StagedBatch batch, List<StagedRow> rows) {
    if (rows.isEmpty()) {
      // Task 3.6 never stages a batch with zero rows (a declared row count of zero fails
      // BatchDeclaration's own construction); an empty list here is a defect elsewhere, not a
      // timing condition to retry.
      throw new IllegalStateException("STAGED batch " + batch.batchId() + " has no staged rows");
    }

    Optional<PlanVersion> planVersionOpt =
        planVersions.resolveAsOf(batch.organizationId(), batch.planId(), batch.valueDate());
    if (planVersionOpt.isEmpty()) {
      return Optional.empty();
    }
    PlanVersion planVersion = planVersionOpt.get();

    LocalDate dealingDate =
        DealingDateRule.dealingDateFor(
            batch.valueDate(), batch.stagedAt(), planVersion.dealingLagBusinessDays(), calendar);

    Optional<Fund> fundOpt = funds.resolveAsOf(planVersion.fundId(), dealingDate);
    if (fundOpt.isEmpty()) {
      return Optional.empty();
    }
    Fund fund = fundOpt.get();

    Optional<FundNav> navOpt = navSource.findOriginal(planVersion.fundId(), dealingDate);
    if (navOpt.isEmpty()) {
      return Optional.empty();
    }
    FundNav nav = navOpt.get();

    // Every movement in this chain — including the two pooled cash legs — is attributed to the
    // organization whose batch is being allotted (ADR-0005): it is what lets projection.transfer
    // partition tenant-first. The pooled fund-level accounts themselves carry no tenant.
    TenantRef batchTenant = tenantRefs.derive(batch.organizationId());

    boolean employerImmediateVesting = planVersion.vestingFullYears() == 0;

    AccountId employerCollections =
        accountIds.derive(
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, batch.organizationId().scope());
    AccountId allotmentSuspense =
        accountIds.derive(LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE, fund.fundId().toString());
    AccountId fundCash =
        accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fund.fundId().toString());
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fund.fundId().toString());

    // EMPLOYER_COLLECTIONS is not provisioned here — it is created once, at organization
    // registration (OrganizationService), and its absence would mean an org was never
    // properly onboarded, which the ledger should reject loudly rather than this service
    // papering over. The three fund-level accounts below have no earlier registration event
    // to hang from (Task 20.1's fund registry writes only reference.fund*, never a ledger
    // account), so this is their first opportunity to exist, the same way
    // OrganizationService provisions EMPLOYER_COLLECTIONS at its own first opportunity.
    // createAccount is idempotent by construction (AccountIdDeriver's javadoc) — every call
    // after the fund's first-ever allotment is a cheap no-op, not a race.
    //
    // Collected here and opened in one createAccounts call below, together with every
    // participant holding the loop finds — never one request per account (row 26p, TASK-LOG.md
    // step 112: ~8,000 one-account requests were ~70% of a batch's time, and the ledger's
    // per-request ceiling is what stopped more replicas from helping). Keyed by id, so a
    // participant's employee and voluntary legs, which land in the same vested holding, open
    // it once.
    Map<AccountId, CreateAccountCommand> accountsToOpen = new LinkedHashMap<>();
    accountsToOpen.put(
        allotmentSuspense,
        new CreateAccountCommand(
            allotmentSuspense,
            LedgerCodes.CASH_LEDGER_ID,
            LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE));
    accountsToOpen.put(
        fundCash,
        new CreateAccountCommand(
            fundCash, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.FUND_CASH));
    accountsToOpen.put(
        unitsInIssue,
        new CreateAccountCommand(
            unitsInIssue, fund.unitLedgerId(), LedgerCodes.AccountCode.UNITS_IN_ISSUE));

    List<CreateTransferCommand> chain = new ArrayList<>();
    List<AllotmentRecord> auditRows = new ArrayList<>();
    long totalMinorUnits = 0;
    long unitsIssuedScaled = 0;
    int legsProcessed = 0;

    for (StagedRow row : rows) {
      for (ContributionSource source : ContributionSource.values()) {
        long amountMinorUnits = row.amountOf(source);
        if (amountMinorUnits <= 0) {
          continue;
        }
        totalMinorUnits += amountMinorUnits;
        legsProcessed++;

        long unitsScaled = floorUnits(amountMinorUnits, nav.nav().scaled());
        if (unitsScaled <= 0) {
          // A contribution too small, at this price, to buy a single tick. The cash still
          // enters FUND_CASH via the batch-total cash legs below, and the whole of this leg's
          // amount becomes part of the run's residual (Task 21.3) below -- no unit-side
          // posting exists to skip.
          continue;
        }
        unitsIssuedScaled += unitsScaled;

        boolean vested = source != ContributionSource.EMPLOYER || employerImmediateVesting;
        LedgerCodes.AccountCode holdingAccountCode =
            vested
                ? LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED
                : LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED;
        AccountId holding =
            accountIds.derive(
                holdingAccountCode,
                participantScope(batch, row.participantInternalRef(), fund.fundId()));
        // A participant's first-ever contribution to this fund: nothing else in the
        // platform has a reason to open this account before now. Scoped to the organization
        // (ADR-0005) so the sparse unit projection (Task 5.5) reads it off accountUserData64.
        accountsToOpen.putIfAbsent(
            holding,
            new CreateAccountCommand(holding, fund.unitLedgerId(), holdingAccountCode)
                .withTenant(batchTenant));

        TransferId transferId =
            transferIds.derive(
                batch.organizationId().scope(),
                batch.batchId() + ":" + row.participantInternalRef() + ":" + source.name());

        chain.add(
            CreateTransferCommand.Unit.single(
                    transferId,
                    unitsInIssue,
                    holding,
                    fund.unitLedgerId(),
                    LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                    Units.of(unitsScaled))
                .withTenant(batchTenant));

        auditRows.add(
            new AllotmentRecord(
                batch.organizationId(),
                batch.batchId(),
                row.participantInternalRef(),
                source,
                fund.fundId(),
                nav.navVersionId(),
                Units.of(unitsScaled),
                vested,
                transferId,
                clock.instant()));
      }
    }

    Money total = Money.of(totalMinorUnits, CurrencyCode.MXN);
    TransferId collectionsToSuspense =
        transferIds.derive(
            batch.organizationId().scope(), batch.batchId() + ":collections_to_suspense");
    TransferId suspenseToFundCash =
        transferIds.derive(
            batch.organizationId().scope(), batch.batchId() + ":suspense_to_fund_cash");

    // Cash legs first, unit legs after — the order chart-of-accounts.md §6's worked example
    // posts them in. Order carries no atomicity meaning of its own (createLinkedChain is
    // all-or-nothing regardless), but a chain that reads in the same order the spec does is
    // one fewer thing a reviewer has to translate.
    List<CreateTransferCommand> ordered = new ArrayList<>(chain.size() + 2);
    ordered.add(
        CreateTransferCommand.Cash.single(
                collectionsToSuspense,
                employerCollections,
                allotmentSuspense,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                total)
            .withTenant(batchTenant));
    ordered.add(
        CreateTransferCommand.Cash.single(
                suspenseToFundCash,
                allotmentSuspense,
                fundCash,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                total)
            .withTenant(batchTenant));
    ordered.addAll(chain);

    // A chain longer than the ledger's own request can hold is not slow, degraded or
    // partially applied -- the request never forms, and TigerBeetle reports it as
    // unavailability rather than as a rejection, which is indistinguishable from a broken
    // cluster at exactly the moment an operator needs to tell those apart (TASK-LOG.md step
    // 40 measured it: 8,189 accepted, 8,190 refused). ADR-0017 makes contribution-api size a
    // batch so this can never happen; this says so where the chain is actually built, so a
    // future change on either side fails here, by name, instead of at a socket.
    if (ordered.size() > LedgerLimits.MAX_LINKED_CHAIN_LENGTH) {
      throw new IllegalStateException(
          "Batch "
              + batch.batchId()
              + " would post "
              + ordered.size()
              + " transfers in one linked chain, over the ledger's limit of "
              + LedgerLimits.MAX_LINKED_CHAIN_LENGTH
              + " -- it was staged by a version that did not size it (ADR-0017)");
    }

    // Before the chain, as one-at-a-time ensureAccount calls were: every leg's accounts must
    // exist when the chain posts, and an absent EMPLOYER_COLLECTIONS still fails the chain
    // itself, by name.
    ledger.createAccounts(List.copyOf(accountsToOpen.values()));

    // Checked before sending, never resent-and-inspected: TigerBeetle's own linked-chain
    // idempotency does not report AlreadyExists per leg the way a standalone transfer does.
    // A resubmission where every id in the chain already exists surfaces as
    // LinkedEventFailed on the surrounding legs instead, because "already exists" inside a
    // LINKED sequence is evaluated as part of the chain's atomicity, not independently per
    // event. Since this whole chain is only ever created together, the first leg's own
    // presence already proves the entire original chain landed — so a rerun that finds it
    // skips resending the chain altogether rather than relying on the ledger to say so a
    // second way.
    if (ledger.lookupTransfer(collectionsToSuspense).isEmpty()) {
      List<TransferResult> results = ledger.createLinkedChain(ordered);
      for (TransferResult result : results) {
        if (result instanceof TransferResult.Rejected rejected) {
          metrics.transferRejected(rejected.reason());
          throw new IllegalStateException(
              "CONTRIBUTION_ALLOTMENT chain rejected for batch "
                  + batch.batchId()
                  + ": "
                  + rejected.reason());
        }
      }
      metrics.transfersCreated(LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT, results.size());
    }

    allotments.recordAll(auditRows);

    if (legsProcessed > 0) {
      recordResidual(batch, fund, nav, legsProcessed, unitsIssuedScaled, total);
    }

    return Optional.of(dealingDate);
  }

  /**
   * Task 21.3 ({@code docs/rounding-policy.md}): {@link #floorUnits} discards a sub-tick fraction
   * of a unit on nearly every leg. The discarded value is never posted — {@code UNITS_IN_ISSUE} is
   * debited by exactly {@code unitsIssuedScaled}, nothing more — so it must be recorded instead.
   *
   * <p>{@code residual = cash allotted − value(units issued)}, computed once over the run's totals
   * rather than accumulated leg by leg: {@link Money#value} already performs the same exact {@link
   * BigInteger} division {@link #floorUnits} inverts, so the two exactly cancel and {@code
   * residual} is non-negative by construction — flooring can only ever leave cash on the table,
   * never take more units than the cash bought.
   *
   * @throws IllegalStateException the residual is negative (units were over-issued somewhere) or
   *     exceeds the run's ceiling — one sub-tick fraction's worth of value per leg processed. A run
   *     this far outside bound is an arithmetic bug, not something to absorb quietly.
   */
  private void recordResidual(
      StagedBatch batch,
      Fund fund,
      FundNav nav,
      int legsProcessed,
      long unitsIssuedScaled,
      Money totalCash) {
    Money unitsValue = Money.value(Units.of(unitsIssuedScaled), nav.nav());
    Money residual = totalCash.minus(unitsValue);
    if (residual.isNegative()) {
      throw new IllegalStateException(
          "Allotment residual for batch "
              + batch.batchId()
              + " is negative ("
              + residual.cents()
              + " cents) — units were over-issued");
    }

    long ceilingCents = ceilingResidualCents(legsProcessed, nav.nav().scaled());
    if (residual.cents() > ceilingCents) {
      throw new IllegalStateException(
          "Allotment residual for batch "
              + batch.batchId()
              + " ("
              + residual.cents()
              + " cents) exceeds its ceiling ("
              + ceilingCents
              + " cents) for "
              + legsProcessed
              + " legs — the arithmetic is wrong, not the residue");
    }

    residuals.record(
        new AllotmentResidual(
            batch.organizationId(),
            batch.batchId(),
            fund.fundId(),
            nav.navVersionId(),
            legsProcessed,
            residual,
            clock.instant()));
  }

  /**
   * {@code ceil(legsProcessed × navScaled ÷ 1e14)} — the cash value, rounded generously upward, of
   * one sub-tick fraction of a unit per leg, at this run's NAV. Each floor operation in the loop
   * above discards a raw remainder strictly less than {@code navScaled} (by construction: {@code
   * floorUnits}'s own division), so the true residual can never exceed this sum — a run that does
   * has an arithmetic bug, the same class of bug the 1e6 factor error in {@link #floorUnits} was
   * (see that method's own javadoc).
   */
  private static long ceilingResidualCents(int legsProcessed, long navScaled) {
    BigInteger[] quotientAndRemainder =
        BigInteger.valueOf(legsProcessed)
            .multiply(BigInteger.valueOf(navScaled))
            .divideAndRemainder(BigInteger.valueOf(100_000_000_000_000L));
    BigInteger ceiling =
        quotientAndRemainder[1].signum() == 0
            ? quotientAndRemainder[0]
            : quotientAndRemainder[0].add(BigInteger.ONE);
    return ceiling.longValueExact();
  }

  /**
   * {@code units = amountMinorUnits × 1e14 ÷ navScaled}, floored.
   *
   * <p><b>1e14, not the 1e8 phase-21-allotment.md's prose names</b> — the combined scale is the
   * exact inverse of {@code Money#value(Units, Nav)}, this codebase's one other place units and a
   * NAV cross a currency boundary: {@code Money.value} multiplies a 1e8-scaled {@code Units} by a
   * 1e8-scaled {@code Nav} (combined 1e16) and divides by 1e14 to land on cents (1e2) — {@code 8 +
   * 8 - 2 = 14}. This method is that division run backwards: cents (1e2) times units' scale (1e8)
   * is 1e10, and reaching a 1e8-scaled units quantity from a 1e8-scaled NAV needs one more 1e8 of
   * headroom before dividing, i.e. {@code 1e10 × 1e8 ÷ 1e8 = amountMinorUnits × 1e14 ÷ navScaled} —
   * verified against chart-of-accounts.md §6's own worked example: MXN 3,000.00 (300,000 cents) at
   * NAV 10.00000000 (nav_scaled 1,000,000,000) yields 300,000 × 1e14 ÷ 1e9 = 30,000,000,000 =
   * 300.00000000 units, exactly the worked example's number. The literal {@code × 1e8} reading is
   * off by a factor of 10^6 and was caught by {@code
   * AllotmentServiceTest#a_batch_allots_to_the_cent_and_to_the_unit_matching_the_worked_example}
   * before it reached the acceptance criteria.
   *
   * <p>{@link BigInteger} rather than {@code long} arithmetic, matching {@code NavPerUnit#strike}'s
   * own choice: an intermediate product this large is worth not gambling on overflow for.
   */
  private static long floorUnits(long amountMinorUnits, long navScaled) {
    return BigInteger.valueOf(amountMinorUnits)
        .multiply(BigInteger.valueOf(100_000_000_000_000L))
        .divide(BigInteger.valueOf(navScaled))
        .longValueExact();
  }

  /**
   * The scope a per-participant, per-fund account ({@code PARTICIPANT_HOLDINGS_VESTED} / {@code
   * _UNVESTED}) is derived under. Organization-scoped first (AGENTS.md §4: every table, message key
   * and query is scoped by tenant first) — {@code participantInternalRef} alone is only unique
   * within an organization, never globally.
   */
  private static String participantScope(
      StagedBatch batch, String participantInternalRef, FundId fundId) {
    return batch.organizationId().scope() + ":" + participantInternalRef + ":" + fundId;
  }
}
