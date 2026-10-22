package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.allotmentjob.allotment.AllotmentMetrics;
import dev.fondaria.allotmentjob.fund.FundDirectory;
import dev.fondaria.allotmentjob.participant.ParticipantDirectory;
import dev.fondaria.allotmentjob.participant.VestingParticipant;
import dev.fondaria.allotmentjob.plan.PlanVersionDirectory;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.VestingScheduleCalculator;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The vesting job's business logic (phase-21-allotment.md, Task 21.4): moves units {@code
 * PARTICIPANT_HOLDINGS_UNVESTED} → {@code PARTICIPANT_HOLDINGS_VESTED} as a participant's
 * employer-sourced lots cross the schedule {@link VestingScheduleCalculator} computes from their
 * {@code employmentStartDate} (ADR-0004: "vesting is a scheduled job producing ledger transfers,
 * not a flag").
 *
 * <p><b>Vesting changes entitlement, never ownership.</b> The units this moves were already issued
 * at contribution and have earned fund returns since (chart-of-accounts.md §7); this class never
 * re-issues, never re-prices, and touches no cash account at all.
 *
 * <p><b>The target, not a running total, is recomputed every time.</b> Given a candidate's {@code
 * employerUnvestedBasis} — the sum of every {@code EMPLOYER}-sourced unit ever posted to that
 * participant's unvested account — and the vested fraction {@link VestingScheduleCalculator}
 * reports for today, the target vested amount is {@code basis × bps ÷ 10000}. Comparing that target
 * against how much has already moved (basis minus the account's own current balance, read live
 * through {@link LedgerPort}) yields exactly the delta still owed, so a rerun on a day nothing
 * changed always computes a non-positive delta and moves nothing — no separate "already vested"
 * bookkeeping is needed, and no state this class owns can drift from the ledger's own truth.
 */
@Service
public class VestingService {

  private final ParticipantDirectory participants;
  private final PlanVersionDirectory planVersions;
  private final FundDirectory funds;
  private final BusinessCalendar calendar;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final TransferIdDeriver transferIds;
  private final TenantRefDeriver tenantRefs;
  private final Clock clock;
  private final AllotmentMetrics metrics;

  public VestingService(
      ParticipantDirectory participants,
      PlanVersionDirectory planVersions,
      FundDirectory funds,
      BusinessCalendar calendar,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      TransferIdDeriver transferIds,
      TenantRefDeriver tenantRefs,
      Clock clock,
      AllotmentMetrics metrics) {
    this.participants = Objects.requireNonNull(participants, "participants must not be null");
    this.planVersions = Objects.requireNonNull(planVersions, "planVersions must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.transferIds = Objects.requireNonNull(transferIds, "transferIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
  }

  /**
   * Attempts to vest one candidate's outstanding employer lots up to today's schedule. Empty when
   * the candidate is not ready (the participant, its plan version as of {@code
   * employmentStartDate}, the fund, or the unvested account itself does not resolve yet) — the
   * caller retries next sweep. Present, holding the units actually moved (zero when nothing was due
   * today), otherwise.
   *
   * @throws IllegalStateException the ledger rejected the transfer — a genuine failure, since every
   *     account this method writes to was already opened by Task 21.2's own allotment run
   */
  public Optional<Units> vest(VestingCandidate candidate) {
    Optional<VestingParticipant> participantOpt =
        participants.find(candidate.organizationId(), candidate.participantInternalRef());
    if (participantOpt.isEmpty()) {
      return Optional.empty();
    }
    VestingParticipant participant = participantOpt.get();

    // Both the VESTING movement and the vested account it may open belong to this participant's
    // organization (ADR-0005) — the unvested account was already stamped with it at allotment.
    TenantRef tenant = tenantRefs.derive(candidate.organizationId());

    // Resolved against employmentStartDate, not today: the schedule that governs a
    // participant's vesting is the one in force when their service clock started, not
    // whichever version happens to be live now (phase-21-allotment.md Task 21.4).
    Optional<PlanVersion> planVersionOpt =
        planVersions.resolveAsOf(
            candidate.organizationId(), participant.planId(), participant.employmentStartDate());
    if (planVersionOpt.isEmpty()) {
      return Optional.empty();
    }
    PlanVersion planVersion = planVersionOpt.get();

    LocalDate asOf = calendar.businessDateOf(clock.instant());

    Optional<Fund> fundOpt = funds.resolveAsOf(candidate.fundId(), asOf);
    if (fundOpt.isEmpty()) {
      return Optional.empty();
    }
    Fund fund = fundOpt.get();

    String scope =
        candidate.organizationId().scope()
            + ":"
            + candidate.participantInternalRef()
            + ":"
            + candidate.fundId();
    AccountId unvestedAccountId =
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope);
    Optional<LedgerAccount> unvestedAccountOpt = ledger.lookupAccount(unvestedAccountId);
    if (unvestedAccountOpt.isEmpty()) {
      return Optional.empty();
    }
    long currentUnvestedScaled = ((LedgerAccount.Unit) unvestedAccountOpt.get()).balance().scaled();
    if (currentUnvestedScaled == 0) {
      // Every employer unit this candidate ever received has already moved to vested --
      // nothing left to do, ever again, for this (participant, fund) pair.
      return Optional.of(Units.of(0));
    }

    long basisScaled = candidate.employerUnvestedBasis().scaled();
    long vestedBps =
        VestingScheduleCalculator.vestedBasisPoints(
            planVersion, participant.employmentStartDate(), asOf);
    long targetScaled = floorTarget(basisScaled, vestedBps);
    long alreadyVestedScaled = basisScaled - currentUnvestedScaled;
    long deltaScaled = targetScaled - alreadyVestedScaled;
    if (deltaScaled <= 0) {
      // Nothing newly due today. A non-positive delta only ever means the schedule has not
      // advanced past what already moved -- never a reason to move units backward.
      return Optional.of(Units.of(0));
    }

    AccountId vestedAccountId =
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope);
    // A candidate whose every employer lot has always landed unvested may never have had an
    // occasion to open PARTICIPANT_HOLDINGS_VESTED before -- idempotent by construction
    // (AccountIdDeriver's own javadoc), so a participant who already holds one is a no-op.
    ledger.createAccount(
        new CreateAccountCommand(
                vestedAccountId,
                fund.unitLedgerId(),
                LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED)
            .withTenant(tenant));

    TransferId transferId =
        transferIds.derive(
            candidate.organizationId().scope(),
            "VESTING:"
                + candidate.participantInternalRef()
                + ":"
                + candidate.fundId()
                + ":"
                + asOf);
    TransferResult result =
        ledger.createTransfer(
            CreateTransferCommand.Unit.single(
                    transferId,
                    unvestedAccountId,
                    vestedAccountId,
                    fund.unitLedgerId(),
                    LedgerCodes.TransferCode.VESTING,
                    Units.of(deltaScaled))
                .withTenant(tenant));
    if (result instanceof TransferResult.Rejected rejected) {
      metrics.transferRejected(rejected.reason());
      throw new IllegalStateException(
          "VESTING transfer rejected for participant "
              + candidate.participantInternalRef()
              + ", fund "
              + candidate.fundId()
              + ": "
              + rejected.reason());
    }
    if (result instanceof TransferResult.Created) {
      // Not on AlreadyExists: this transferId is deterministic (participant, fund, asOf), so a
      // concurrent rerun that races this same delta lands here once as Created and once as
      // AlreadyExists -- counting the second copy would overstate transfer_created_total for a
      // movement that only ever happened once.
      metrics.transfersCreated(LedgerCodes.TransferCode.VESTING, 1);
    }
    return Optional.of(Units.of(deltaScaled));
  }

  /** {@code floor(basisScaled × bps ÷ 10000)} — integer arithmetic only (AGENTS.md §4). */
  private static long floorTarget(long basisScaled, long bps) {
    return BigInteger.valueOf(basisScaled)
        .multiply(BigInteger.valueOf(bps))
        .divide(BigInteger.valueOf(10_000L))
        .longValueExact();
  }
}
