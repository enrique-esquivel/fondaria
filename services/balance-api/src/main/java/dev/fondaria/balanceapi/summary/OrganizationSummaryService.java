package dev.fondaria.balanceapi.summary;

import dev.fondaria.balanceapi.balance.PricesUnavailableException;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.NavCache;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The employer-facing aggregate (Task 6.5): how many participants an organization has, what they
 * hold in each fund, what it is worth, and what the employer contributed this period.
 *
 * <h2>Why the total is built out of participants and not out of one number</h2>
 *
 * <p>Nothing in the ledger holds an organization's unit position. {@code UNITS_IN_ISSUE} is
 * per-fund and platform-wide, so it says what <i>every</i> employer holds together; {@code
 * EMPLOYER_COLLECTIONS} is per-organization but is cash, and is back at zero once a batch has
 * allotted. The only place an employer's units exist is across its participants' own holding
 * accounts — so the summary reads exactly those, in batches, and adds them up.
 *
 * <p><b>That is what makes the totals tie.</b> The candidate funds are {@code
 * NavCache.currentAll()}, the account ids are derived by {@code ParticipantScope.forFund}, and each
 * lot is valued by {@code Money.value} — the same three things {@code BalanceService} does, in the
 * same order, with the same rounding. The first acceptance criterion is therefore structural rather
 * than arithmetical: this service cannot diverge from the sum of the balances it aggregates without
 * one of those three changing under it.
 *
 * <p><b>The tie is a quiescent-ledger property, and honestly so.</b> The roster is read, then
 * walked in slices, then the contributions figure is queried — tens of sequential ledger round
 * trips at 10,000 participants, over a thousand at 200,000. A transfer committed mid-walk is
 * counted for a late participant and missed for an early one, so this is not an instantaneous
 * snapshot and does not tie to what the balance endpoint would serve at any single instant while
 * money is moving. {@code asOf} says when the read <i>began</i>. Employer aggregates are read
 * against a book that moves in batches at the strike, not continuously, which is why that is
 * acceptable here and would not be for a participant's own balance.
 *
 * <h2>Cost, and where the sizes come from</h2>
 *
 * <p>{@code O(participants)} is unavoidable here and correct — the answer is about all of them.
 * What is avoided is a round trip per participant: the roster is read whole, in <b>one</b> indexed
 * statement, and then walked in slices sized so that a slice's derived ids are one batched {@code
 * lookupAccounts}. A 10,000-employee organization in one fund is one query and three ledger calls,
 * not 10,000 of each. The batch cap itself is enforced by {@link #lookupInBatches} rather than by
 * the slice size, which cannot shrink below one participant.
 *
 * <p><b>The roster is not paged, deliberately</b>, and {@link OrganizationRoster} records why at
 * length: keyset pagination over a {@code text} column can silently skip participants and
 * under-report an employer's money. The memory that buys back — one list of internal references per
 * request — is the accepted cost.
 *
 * <h2>Tenant isolation</h2>
 *
 * <p><b>Every account id in the request is derived under the organization in the path.</b> Not
 * looked up, not read off a row, not carried by anything the caller supplies — derived, from that
 * one id. So even a roster that wrongly returned another employer's participant could not put
 * another employer's units into this total: the derivation would produce account ids under this
 * organization's scope, which that participant does not hold. The same is true of the contributions
 * figure, whose {@code tenant_id} and {@code EMPLOYER_COLLECTIONS} account are derived from the
 * same id.
 *
 * <p><b>What this does not do is authenticate the caller.</b> This endpoint ships unauthenticated
 * in v1 like every other one here, and must not be exposed outside the cluster until Phase 9/10
 * lands. "An employer cannot query another organization" is, today, the property that a request
 * naming organization A can return nothing but A's data — not that the caller was proved to be A.
 */
public class OrganizationSummaryService {

  /**
   * How many account ids one {@code lookupAccounts} may carry.
   *
   * <p><b>The binding constraint is the reply, not the request.</b> Sixteen-byte ids pack tens of
   * thousands into a 1 MiB message, but each result comes back as a 128-byte {@code Account}, which
   * caps a {@code lookup_accounts} at 8,189 of them — and an over-long batch is rejected rather
   * than split. (Do not carry this number to {@code createTransfers}: there the 128-byte events are
   * what fills the <i>request</i>, and 8,189 is the limit for a different reason.) 8,000 leaves
   * headroom without another knob: a constant, because it is a property of the pinned ledger rather
   * than of a deployment, and the two things an operator might legitimately tune here — the pool
   * and the statement timeout — already exist.
   */
  static final int MAX_ACCOUNT_IDS_PER_LOOKUP = 8_000;

  private static final List<LedgerCodes.AccountCode> HOLDING_CODES =
      List.of(
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED);

  private final OrganizationRoster roster;
  private final LedgerPort ledger;
  private final NavCache navCache;
  private final ContributionTotalRepository contributions;
  private final AccountIdDeriver accountIds;
  private final TenantRefDeriver tenantRefs;
  private final Clock clock;
  private final ZoneId zone;

  public OrganizationSummaryService(
      OrganizationRoster roster,
      LedgerPort ledger,
      NavCache navCache,
      ContributionTotalRepository contributions,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs,
      Clock clock,
      ZoneId zone) {
    this.roster = Objects.requireNonNull(roster, "roster must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.navCache = Objects.requireNonNull(navCache, "navCache must not be null");
    this.contributions = Objects.requireNonNull(contributions, "contributions must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
  }

  /**
   * @throws OrganizationNotFoundException when no organization carries {@code organizationId}
   * @throws PricesUnavailableException when the NAV cache holds no prices at all — a {@code 503},
   *     never a zero total
   * @throws dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException when the ledger cannot be
   *     reached — surfaced as {@code 503}, never as a zero total
   */
  public OrganizationSummary summaryOf(OrganizationId organizationId) {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    if (!roster.isRegistered(organizationId)) {
      throw new OrganizationNotFoundException();
    }

    List<CachedNav> prices = navCache.currentAll();
    if (prices.isEmpty()) {
      // The same refusal BalanceService makes, for the same reason: no prices means this instance
      // cannot value anything, which is not the claim "this employer's people hold nothing".
      throw new PricesUnavailableException();
    }

    Instant asOf = clock.instant();
    Totals totals = new Totals(prices);
    int participants = aggregateRoster(organizationId, prices, totals);

    StatementPeriod period = StatementPeriod.monthOf(asOf, zone);
    Money contributed =
        contributions.contributionsIn(
            tenantRefs.derive(organizationId).asUnsignedDecimal(),
            accountIds
                .derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope())
                .asUnsignedDecimal(),
            period);

    return OrganizationSummary.of(
        organizationId, asOf, participants, totals.held(), contributed, period);
  }

  /**
   * Reads the whole roster, then adds its holdings into {@code totals} a slice at a time.
   *
   * <p><b>The slicing is in memory and cannot skip anybody.</b> The roster arrives as one list from
   * one equality predicate — no cursor, no ordering, nothing for a collation to disagree with (see
   * {@link OrganizationRoster}) — and this only decides how many participants' account ids are
   * derived and looked up at once, so that neither the id list nor the ledger request grows with
   * the employer.
   *
   * @return how many participants were counted — the roster this aggregate actually covers, which
   *     is why it is counted here rather than asked for separately. A {@code COUNT(*)} taken beside
   *     the read could disagree with it under a concurrent enrolment, and a participant count that
   *     does not describe the totals next to it is worse than an approximate one.
   */
  private int aggregateRoster(
      OrganizationId organizationId, List<CachedNav> prices, Totals totals) {
    List<String> internalRefs = roster.participantsOf(organizationId);
    int sliceSize = holdersPerLookup(prices.size());
    for (int from = 0; from < internalRefs.size(); from += sliceSize) {
      int to = Math.min(from + sliceSize, internalRefs.size());
      addHoldings(organizationId, internalRefs.subList(from, to), prices, totals);
    }
    return internalRefs.size();
  }

  /**
   * How many participants' holdings are derived and looked up at once.
   *
   * <p>Two accounts per fund per participant, so the slice shrinks as the fund registry grows, and
   * a slice is one ledger call in any registry this platform will plausibly run. Never zero: a
   * zero-sized slice would make no progress. Past roughly 4,000 funds a single participant's own
   * ids already exceed one batch, so this stops being able to size a slice down far enough — which
   * is why {@link #lookupInBatches} enforces the cap rather than trusting this to.
   */
  private static int holdersPerLookup(int fundCount) {
    return Math.max(1, MAX_ACCOUNT_IDS_PER_LOOKUP / (fundCount * HOLDING_CODES.size()));
  }

  /** One slice of participants: derive every holding account, look them all up at once, add. */
  private void addHoldings(
      OrganizationId organizationId,
      List<String> internalRefs,
      List<CachedNav> prices,
      Totals totals) {
    List<AccountId> ids =
        new ArrayList<>(internalRefs.size() * prices.size() * HOLDING_CODES.size());
    Map<AccountId, FundId> fundOfAccount = new HashMap<>();
    for (String internalRef : internalRefs) {
      ParticipantScope scope = new ParticipantScope(organizationId, internalRef);
      for (CachedNav price : prices) {
        String accountScope = scope.forFund(price.fundId());
        for (LedgerCodes.AccountCode code : HOLDING_CODES) {
          AccountId id = accountIds.derive(code, accountScope);
          ids.add(id);
          fundOfAccount.put(id, price.fundId());
        }
      }
    }

    // Accounts that were never opened simply do not come back — a participant who has never been
    // allotted into a fund contributes nothing, which is the same answer BalanceService gives them
    // individually.
    for (LedgerAccount account : lookupInBatches(ids)) {
      FundId fundId = fundOfAccount.get(account.id());
      if (fundId == null) {
        // Only ids derived above were asked for. Anything else would mean the ledger answered a
        // question nobody put to it, and adding it would put units of unknown provenance into an
        // employer's total.
        throw new IllegalStateException(
            "the ledger returned an account that was not one of this organization's holdings");
      }
      totals.add(fundId, unitsOf(account));
    }
  }

  /**
   * Looks {@code ids} up, never handing the port more than {@link #MAX_ACCOUNT_IDS_PER_LOOKUP} at
   * once.
   *
   * <p><b>The cap is enforced here rather than left to the page size.</b> {@code
   * LedgerPort.lookupAccounts} does not split a batch against TigerBeetle's own limit — a gap
   * recorded in {@code BACKLOG.md} — and an over-long request fails, as a {@code 500}, because the
   * throw bypasses the wrapper that produces {@code LedgerUnavailableException}. Sizing the roster
   * page is what keeps this to <i>one</i> call in every realistic registry; this loop is what makes
   * the bound true even when a single participant's own ids exceed a batch, which happens past
   * roughly 4,000 funds and which no page size can fix.
   */
  private List<LedgerAccount> lookupInBatches(List<AccountId> ids) {
    if (ids.size() <= MAX_ACCOUNT_IDS_PER_LOOKUP) {
      return ledger.lookupAccounts(ids);
    }
    List<LedgerAccount> found = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += MAX_ACCOUNT_IDS_PER_LOOKUP) {
      int to = Math.min(from + MAX_ACCOUNT_IDS_PER_LOOKUP, ids.size());
      found.addAll(ledger.lookupAccounts(ids.subList(from, to)));
    }
    return found;
  }

  /**
   * A holding account is on a unit ledger by construction — the same invariant {@code
   * BalanceService} relies on, restated here rather than shared because the two read the ledger
   * independently and a helper passed between them would hide that. Reading one back as cash would
   * mean the id derivation collided with a cash account, which would make every figure downstream
   * wrong; it fails loudly rather than being skipped.
   */
  private static Units unitsOf(LedgerAccount account) {
    if (account instanceof LedgerAccount.Unit unit) {
      return unit.balance();
    }
    throw new IllegalStateException(
        "a participant holding account resolved to a cash-ledger account: "
            + account.accountCode());
  }

  /**
   * The per-fund accumulators, one per priced fund.
   *
   * <p><b>Value is accumulated lot by lot, not derived from the unit total at the end.</b> Each
   * account contributes {@code Money.value(itsUnits, nav)} — one rounding per lot, exactly as the
   * participant's own balance rounds it — and the fund's value is the sum of those. See {@code
   * OrganizationFundTotal} and {@code ADR-0015} for why that, and not the rounded aggregate
   * product, is the figure an employer is shown.
   */
  private static final class Totals {

    private final Map<FundId, CachedNav> prices = new LinkedHashMap<>();
    private final Map<FundId, Units> unitsOfFund = new LinkedHashMap<>();
    private final Map<FundId, Money> valueOfFund = new LinkedHashMap<>();

    private Totals(List<CachedNav> prices) {
      for (CachedNav price : prices) {
        this.prices.put(price.fundId(), price);
        this.unitsOfFund.put(price.fundId(), Units.of(0));
        this.valueOfFund.put(price.fundId(), Money.of(0, CurrencyCode.MXN));
      }
    }

    /**
     * Both accumulators are overflow-checked — {@code Units.plus} and {@code Money.plus} are {@code
     * addExact}. An employer's unit total is the figure most likely to reach the top of a long
     * first, and a silently wrapped one is a wrong number about money.
     */
    private void add(FundId fundId, Units units) {
      CachedNav price = prices.get(fundId);
      unitsOfFund.merge(fundId, units, Units::plus);
      valueOfFund.merge(fundId, Money.value(units, price.nav()), Money::plus);
    }

    /**
     * The funds that hold something, ordered by fund id.
     *
     * <p>A fund the organization holds nothing in is absent rather than reported as a zero row —
     * the same rule {@code FundPosition.isHeld} applies to a participant's positions. There is no
     * "or negative" case to keep visible here as there is there: a holding account carries {@code
     * debits_must_not_exceed_credits}, and {@code Units} refuses a negative value outright, so a
     * total that could be understated by dropping it cannot be constructed.
     */
    private List<OrganizationFundTotal> held() {
      List<OrganizationFundTotal> funds = new ArrayList<>(prices.size());
      prices.forEach(
          (fundId, price) -> {
            Units units = unitsOfFund.get(fundId);
            if (units.isZero()) {
              return;
            }
            funds.add(new OrganizationFundTotal(fundId, units, price, valueOfFund.get(fundId)));
          });
      funds.sort(Comparator.comparing(fund -> fund.fundId().value()));
      return funds;
    }
  }
}
