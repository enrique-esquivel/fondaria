package dev.fondaria.balanceapi.movement;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.TenantRefDeriver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A participant's movement history: keyset-paginated pages of the unit-ledger entries on their own
 * holding accounts (Task 6.3).
 *
 * <h2>How a participant's rows are found without a join</h2>
 *
 * <p>The CDC stream carries no participant key and no fund key — {@code projection.transfer} holds
 * the two <i>account ids</i> and the ledger id, and nothing else (Task 5.4/5.5). So the lookup runs
 * in the other direction: this service derives the participant's holding account ids exactly as
 * {@code AllotmentService} derived them when it opened the accounts, and asks for rows on those.
 * Deriving is what {@code BalanceService} already does for the same participant, under the same
 * namespace; the two must never drift, and a mismatch would not fail — it would report an empty
 * history for someone who has one.
 *
 * <p>The {@code tenant_id} that prunes the partitions is derived the same way, from the
 * organization ({@code TenantRef}, ADR-0005's 2026-08-28 amendment) — the read side computes it as
 * a pure function rather than looking it up, exactly as {@code TenantRef}'s own javadoc
 * anticipated.
 *
 * <h2>What the cost of a page is a function of</h2>
 *
 * <p>The candidate funds come from the registry ({@code FundDirectory}), and each contributes four
 * query branches — two holding accounts, either side of a transfer. So a page costs {@code
 * O(funds)} index scans and <b>never {@code O(participants)}</b>, which is the property this phase
 * is about. It does mean the work grows with the number of funds the platform has ever registered;
 * at v1's handful ({@code ADR-0012}) that is the right trade, and narrowing it to the funds a
 * participant has actually held is not available today because the CDC stream carries no
 * participant key to narrow by — see {@code TASK-LOG.md} step 33.
 *
 * <h2>Trimming, and why the repository is asked for more than a page</h2>
 *
 * <p>The SQL takes everything at or below the cursor's transfer (see {@link
 * MovementPageStatement}); at most two rows share a {@code (timestamp, transfer_id)}, so at most
 * two are discarded here. One more row on top of that answers "is there another page" without a
 * second query. Asking for {@code limit + 3} covers both.
 *
 * <h2>What pagination guarantees, and what it does not</h2>
 *
 * <p>Pages descend from a position fixed by the first page, so a movement committed <i>while</i> a
 * client is paging is newer than that position and is simply not in this pass — never a duplicate,
 * never a row pushed past the reader by a shifting offset. The one thing keyset ordering cannot
 * absorb is a row that arrives <i>below</i> a position already passed, which for this projection
 * means a CDC redelivery landing out of order after the reader has gone by. It is a gap in a pass,
 * not a lost row: the projection is append-only and the next pass sees it.
 */
public class MovementService {

  /** The cursor's own row (at most two legs) plus one probe for "is there more". */
  private static final int LOOKAHEAD = 3;

  private final ParticipantDirectory participants;
  private final FundDirectory funds;
  private final MovementRepository movements;
  private final AccountIdDeriver accountIds;
  private final TenantRefDeriver tenantRefs;
  private final CursorCodec cursors;

  public MovementService(
      ParticipantDirectory participants,
      FundDirectory funds,
      MovementRepository movements,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs,
      CursorCodec cursors) {
    this.participants = Objects.requireNonNull(participants, "participants must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.movements = Objects.requireNonNull(movements, "movements must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
    this.cursors = Objects.requireNonNull(cursors, "cursors must not be null");
  }

  /**
   * @throws ParticipantNotFoundException when no participant carries the query's id — a {@code
   *     404}, never an empty page, which is a different and misleading claim
   * @throws TamperedCursorException when the query's cursor is not one this service issued for it
   */
  public MovementPage movements(MovementQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    ParticipantScope scope =
        participants.find(query.participantId()).orElseThrow(ParticipantNotFoundException::new);

    List<FundId> inScope = query.fundId().map(List::of).orElseGet(funds::listFundIds);
    if (inScope.isEmpty()) {
      // No fund is registered, or the caller filtered to one that is not. Either way there is
      // nothing to derive an account id from, and no page to ask the database for.
      return MovementPage.last(List.of());
    }

    Map<String, Holding> holdings = holdingsOf(scope, inScope);
    Optional<MovementCursor> cursor = query.cursor().map(token -> cursors.decode(token, query));

    List<MovementLeg> legs =
        movements.page(
            tenantRefs.derive(scope.organizationId()).asUnsignedDecimal(),
            List.copyOf(holdings.keySet()),
            query,
            cursor,
            query.limit() + LOOKAHEAD);

    // Trim and bound first, interpret second: only rows that are actually on this page are turned
    // into movements, so a data fault on the cursor's own leg or on the "is there more" probe
    // cannot fail a request whose page was sound.
    List<MovementLeg> onPage = new ArrayList<>(query.limit());
    boolean hasMore = false;
    for (MovementLeg leg : legs) {
      if (cursor.isPresent() && !cursor.get().precedes(leg)) {
        continue;
      }
      if (onPage.size() == query.limit()) {
        hasMore = true;
        break;
      }
      onPage.add(leg);
    }

    List<Movement> page = new ArrayList<>(onPage.size());
    for (MovementLeg leg : onPage) {
      page.add(toMovement(leg, holdings));
    }

    if (!hasMore) {
      return MovementPage.last(page);
    }
    return new MovementPage(
        page, Optional.of(cursors.encode(query, MovementCursor.of(page.get(page.size() - 1)))));
  }

  /** Which fund and which of the two holdings each derived account id stands for. */
  private Map<String, Holding> holdingsOf(ParticipantScope scope, List<FundId> inScope) {
    // Insertion-ordered, so the generated SQL text is stable for a given fund set — a
    // HashMap's iteration order would vary the statement, and with it its plan-cache key.
    Map<String, Holding> holdings = new LinkedHashMap<>();
    for (FundId fundId : inScope) {
      String accountScope = scope.forFund(fundId);
      for (HoldingAccount holding : HoldingAccount.values()) {
        AccountId accountId = accountIds.derive(holding.accountCode(), accountScope);
        holdings.put(accountId.asUnsignedDecimal(), new Holding(fundId, holding));
      }
    }
    return holdings;
  }

  private static Movement toMovement(MovementLeg leg, Map<String, Holding> holdings) {
    Holding holding = holdings.get(leg.accountId());
    if (holding == null) {
      // Only ids this service derived were asked for, so a row for any other account means the
      // query returned something it was not asked for. Never guessed at, never dropped.
      throw new IllegalStateException(
          "a movement row resolved to an account that was not one of the participant's holdings");
    }
    return new Movement(
        leg.transferId(),
        holding.fundId(),
        holding.account(),
        leg.direction(),
        Units.of(leg.amountScaled()),
        MovementTransferCode.ofLedgerCode(leg.code()),
        leg.navVersionId(),
        leg.bookingDate(),
        leg.valueDate(),
        leg.ledgerTimestamp());
  }

  /** What an account id means, once it is one of this participant's. */
  private record Holding(FundId fundId, HoldingAccount account) {}
}
