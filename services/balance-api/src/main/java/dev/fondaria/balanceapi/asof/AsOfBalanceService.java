package dev.fondaria.balanceapi.asof;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.movement.HoldingAccount;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A participant's balance as at a past date (Task 6.4): the last projected holding at or before the
 * date, times that date's NAV.
 *
 * <h2>Why this one reads PostgreSQL when the balance never does</h2>
 *
 * <p>The ledger is the authority for a balance and PostgreSQL never is (ADR-0008) — but the ledger
 * has no concept of "as of". {@code lookupAccount} answers what an account holds <i>now</i>;
 * reconstructing what it held in March would mean replaying every transfer since. The sparse {@code
 * projection.participant_units} exists for precisely this question, and this query is low-volume
 * and batch-shaped rather than a hot path (docs/DOMAIN.md). Nothing here sums a balance out of
 * rows: a projected row is an absolute holding the CDC consumer recorded, and the only arithmetic
 * is the multiplication by a price.
 *
 * <h2>How a participant's rows are found without a join</h2>
 *
 * <p>The same way {@code MovementService} finds theirs, and it must stay the same way: the CDC
 * stream carries no participant key, so the holding account ids are <b>derived</b> exactly as
 * {@code AllotmentService} derived them when it opened the accounts, and the {@code tenant_id} that
 * prunes the partitions is derived from the organization ({@code TenantRef}, ADR-0005). Drift in
 * either does not fail — it reads accounts that do not exist and reports a participant as having
 * held nothing, which is why {@code ParticipantScope.forFund} is pinned by its own test.
 *
 * <h2>Vested and unvested are summed here, not reported apart</h2>
 *
 * <p>They are separate accounts and therefore separate series in the projection, each with its own
 * change dates — so both are read, at or before the date, independently. The as-of contract renders
 * one units figure per fund, so they are added and the product rounded once (ADR-0013's rule for
 * this task). Which of the two a historical unit sat in is a question the movement history answers.
 *
 * <h2>The two ways this returns nothing, and why they are different</h2>
 *
 * <ul>
 *   <li><b>No row at or before the date, for any account</b> — {@link NoHoldingOnRecordException},
 *       a {@code 404}. The records do not reach that far back.
 *   <li><b>Rows exist and every one of them is zero</b> — a {@code 200} with an empty position list
 *       and a zero value. The participant held something once and had redeemed it all by then.
 * </ul>
 *
 * <p>Reporting the first as the second is the failure this endpoint exists to avoid. An empty fund
 * registry is neither: it is a {@code 503} ({@link FundRegistryUnavailableException}), because it
 * says nothing about the participant at all.
 *
 * <h2>What this cannot detect</h2>
 *
 * <p><b>A fund missing from {@code reference.fund_identity} that a participant nonetheless holds
 * silently drops out of the total.</b> Its branches match nothing, the remaining funds answer, and
 * the response is a {@code 200} short one position — the partial total {@link
 * PriceNotOnRecordException} exists to refuse. The same blind spot is structural in {@code
 * MovementService}, for the same reason: the projection keys a holding by account id, so there is
 * nothing in it to reconcile the registry against (see {@code TASK-LOG.md} step 33). It needs the
 * end-to-end coverage of Task 6.6, not a check here.
 */
public class AsOfBalanceService {

  private final ParticipantDirectory participants;
  private final FundDirectory funds;
  private final HistoricUnitsRepository holdings;
  private final FundNavRepository navs;
  private final AccountIdDeriver accountIds;
  private final TenantRefDeriver tenantRefs;

  public AsOfBalanceService(
      ParticipantDirectory participants,
      FundDirectory funds,
      HistoricUnitsRepository holdings,
      FundNavRepository navs,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs) {
    this.participants = Objects.requireNonNull(participants, "participants must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.holdings = Objects.requireNonNull(holdings, "holdings must not be null");
    this.navs = Objects.requireNonNull(navs, "navs must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
  }

  /**
   * @throws ParticipantNotFoundException when no participant carries {@code participantId}
   * @throws NoHoldingOnRecordException when nothing was held, in any fund, at or before {@code
   *     date}
   * @throws PriceNotOnRecordException when a fund held on {@code date} has no live NAV for it
   */
  public AsOfBalance balanceAsOf(ParticipantId participantId, LocalDate date) {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(date, "date must not be null");
    ParticipantScope scope =
        participants.find(participantId).orElseThrow(ParticipantNotFoundException::new);

    List<RegisteredFund> registered = funds.listFunds();
    if (registered.isEmpty()) {
      // Not a 404. See FundRegistryUnavailableException: an empty registry is a fact about the
      // platform, and reporting it as "no record this far back" would say something false about
      // every participant on every date.
      throw new FundRegistryUnavailableException();
    }

    DerivedAccounts accounts = accountsOf(scope, registered);
    List<HistoricHolding> asAt =
        holdings.holdingsAsOf(
            tenantRefs.derive(scope.organizationId()).asUnsignedDecimal(), accounts.keys(), date);
    if (asAt.isEmpty()) {
      throw new NoHoldingOnRecordException();
    }

    return AsOfBalance.of(participantId, date, positions(accounts.fundOfAccount(), asAt, date));
  }

  /**
   * Both holding accounts of every registered fund, in a stable order.
   *
   * <p>Insertion-ordered so the generated SQL text is stable for a given fund set — a {@code
   * HashMap}'s iteration order would vary the statement, and with it its plan-cache key.
   */
  private DerivedAccounts accountsOf(ParticipantScope scope, List<RegisteredFund> registered) {
    Map<HoldingAccountKey, FundId> fundOfAccount = new LinkedHashMap<>();
    for (RegisteredFund fund : registered) {
      String accountScope = scope.forFund(fund.fundId());
      for (HoldingAccount holding : HoldingAccount.values()) {
        fundOfAccount.put(
            new HoldingAccountKey(
                accountIds.derive(holding.accountCode(), accountScope).asUnsignedDecimal(),
                fund.unitLedgerId()),
            fund.fundId());
      }
    }
    return new DerivedAccounts(fundOfAccount);
  }

  /**
   * The derived accounts: what each {@code (account, unit ledger)} pair means, and — as its key set
   * — what the repository is asked for.
   *
   * <p><b>Keyed on the pair, not on the account alone.</b> A row is mapped back to a fund only if
   * <i>both</i> halves match what was derived, so a row stored under a different fund than the
   * account belongs to is rejected rather than merged into the wrong position.
   */
  private record DerivedAccounts(Map<HoldingAccountKey, FundId> fundOfAccount) {

    List<HoldingAccountKey> keys() {
      return List.copyOf(fundOfAccount.keySet());
    }
  }

  /**
   * Sums each fund's two holdings, prices the funds that hold something, and drops the ones that do
   * not.
   *
   * <p><b>A fund at zero units gets no price lookup and no position.</b> It is not reported for the
   * same reason the current balance does not report it — a fund holding nothing is absent, not a
   * zero row — and, just as importantly, a fund the participant had fully redeemed out of must not
   * be able to fail the whole request because nobody struck a price for it that day.
   *
   * <p><b>The price is {@code findLive} for that exact date.</b> Never {@code findOriginal}, which
   * would serve a price already known to be wrong; never {@code findLatestLive}, which answers only
   * for a fund's most recent date and would price March at today's NAV.
   */
  private List<AsOfPosition> positions(
      Map<HoldingAccountKey, FundId> fundOfAccount, List<HistoricHolding> asAt, LocalDate date) {
    Map<FundId, Long> unitsOfFund = new LinkedHashMap<>();
    for (HistoricHolding holding : asAt) {
      FundId fundId = fundOfAccount.get(holding.account());
      if (fundId == null) {
        // Only these accounts, on these unit ledgers, were asked for. A row matching neither means
        // the query returned something it was not asked for, or that a holding account is stored
        // under a fund it does not belong to — which would merge two funds' units into one
        // position. Never guessed at, never dropped.
        throw new IllegalStateException(
            "a participant_units row resolved to an account that was not one of the"
                + " participant's holdings");
      }
      unitsOfFund.merge(fundId, holding.units().scaled(), Math::addExact);
    }

    List<AsOfPosition> positions = new ArrayList<>(unitsOfFund.size());
    unitsOfFund.forEach(
        (fundId, scaled) -> {
          if (scaled == 0) {
            return;
          }
          FundNav nav =
              navs.findLive(fundId, date).orElseThrow(() -> new PriceNotOnRecordException(fundId));
          positions.add(AsOfPosition.of(fundId, Units.of(scaled), nav));
        });
    // Stable, so two reads of the same date render their positions in the same order.
    positions.sort(Comparator.comparing(position -> position.fundId().value()));
    return positions;
  }
}
