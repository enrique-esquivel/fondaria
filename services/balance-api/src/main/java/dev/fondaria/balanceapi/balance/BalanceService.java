package dev.fondaria.balanceapi.balance;

import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.NavCache;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The balance read: {@code units × NAV}, and nothing else (Task 6.2).
 *
 * <h2>Why this never touches PostgreSQL</h2>
 *
 * <p>Units come from {@link LedgerPort#lookupAccounts} — the ledger is the authority for a balance
 * and PostgreSQL never is (ADR-0008, ADR-0011). The price comes from {@link NavCache}, in memory,
 * one number per fund. The candidate set of funds comes from {@link NavCache#currentAll()} rather
 * than from {@code reference.fund}, which is what removes the last database read from the request
 * path; the only one left is the participant-id resolution, cached and immutable (see {@code
 * CachingParticipantDirectory}).
 *
 * <h2>Why the cost does not grow with participant count</h2>
 *
 * <p>One cached map lookup, then <b>one</b> batched ledger call for {@code 2 × fundCount} account
 * ids — two accounts per fund, vested and unvested. Nothing iterates participants, nothing scans,
 * nothing aggregates. The work is a function of how many funds the platform runs, which is a
 * handful, and is identical at a hundred participants and at a million.
 *
 * <h2>Absent accounts are the answer, not a gap</h2>
 *
 * <p>{@code lookupAccounts} returns only accounts that exist. A participant's holding accounts are
 * opened by {@code AllotmentService} at their first allotment into a fund, so an absent account
 * means exactly "never held units in this fund" and is reported by that fund's absence from the
 * positions list. This service never opens an account to make a read tidy.
 */
public class BalanceService {

  private final ParticipantDirectory participants;
  private final LedgerPort ledger;
  private final NavCache navCache;
  private final AccountIdDeriver accountIds;
  private final Clock clock;

  public BalanceService(
      ParticipantDirectory participants,
      LedgerPort ledger,
      NavCache navCache,
      AccountIdDeriver accountIds,
      Clock clock) {
    this.participants = Objects.requireNonNull(participants, "participants must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.navCache = Objects.requireNonNull(navCache, "navCache must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * @throws ParticipantNotFoundException when no participant carries {@code participantId}
   * @throws PricesUnavailableException when the NAV cache holds no prices at all — a {@code 503},
   *     never a zero balance
   * @throws dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException when the ledger cannot be
   *     reached — surfaced as {@code 503}, never as a zero balance
   */
  public ParticipantBalance balanceOf(ParticipantId participantId) {
    Objects.requireNonNull(participantId, "participantId must not be null");
    ParticipantScope scope =
        participants.find(participantId).orElseThrow(ParticipantNotFoundException::new);

    List<CachedNav> prices = navCache.currentAll();
    if (prices.isEmpty()) {
      // Never fall through to "no funds, therefore no positions, therefore zero". See
      // PricesUnavailableException: an empty price set means this instance cannot answer,
      // not that the answer is zero.
      throw new PricesUnavailableException();
    }

    // Two derived account ids per fund, and a way back from each to what it represents.
    List<AccountId> ids = new ArrayList<>(prices.size() * 2);
    Map<AccountId, FundId> vestedOf = new HashMap<>();
    Map<AccountId, FundId> unvestedOf = new HashMap<>();
    for (CachedNav price : prices) {
      String accountScope = scope.forFund(price.fundId());
      AccountId vested =
          accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, accountScope);
      AccountId unvested =
          accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, accountScope);
      ids.add(vested);
      ids.add(unvested);
      vestedOf.put(vested, price.fundId());
      unvestedOf.put(unvested, price.fundId());
    }

    // One round trip, whatever the fund count.
    Map<FundId, Units> vestedUnits = new HashMap<>();
    Map<FundId, Units> unvestedUnits = new HashMap<>();
    for (LedgerAccount account : ledger.lookupAccounts(ids)) {
      Units held = unitsOf(account);
      FundId asVested = vestedOf.get(account.id());
      if (asVested != null) {
        vestedUnits.put(asVested, held);
        continue;
      }
      FundId asUnvested = unvestedOf.get(account.id());
      if (asUnvested != null) {
        unvestedUnits.put(asUnvested, held);
      }
      // An id we did not ask for cannot come back; nothing to handle.
    }

    List<FundPosition> positions = new ArrayList<>();
    for (CachedNav price : prices) {
      FundPosition position =
          FundPosition.of(
              price.fundId(),
              vestedUnits.getOrDefault(price.fundId(), Units.of(0)),
              unvestedUnits.getOrDefault(price.fundId(), Units.of(0)),
              price);
      if (position.isHeld()) {
        positions.add(position);
      }
    }

    return ParticipantBalance.of(participantId, clock.instant(), positions);
  }

  /**
   * A holding account is on a unit ledger by construction — {@code PARTICIPANT_HOLDINGS_VESTED} and
   * {@code _UNVESTED} are unit account codes and a cash-ledger account can never carry them.
   * Reading one back as {@link LedgerAccount.Cash} would mean the id derivation collided with a
   * cash account, which would make every figure downstream wrong; it fails loudly rather than being
   * skipped.
   */
  private static Units unitsOf(LedgerAccount account) {
    if (account instanceof LedgerAccount.Unit unit) {
      return unit.balance();
    }
    throw new IllegalStateException(
        "A participant holding account resolved to a cash-ledger account: "
            + account.accountCode());
  }
}
