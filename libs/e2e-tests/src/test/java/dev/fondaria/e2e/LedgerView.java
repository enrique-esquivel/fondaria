package dev.fondaria.e2e;

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

/**
 * The ledger as a reader sees it: every account the {@code CONTRIBUTION_ALLOTMENT} chain touches,
 * read back through {@link LedgerPort#lookupAccount} — which is what Task 21.5's first criterion
 * asks for, and the only way the platform ever reads a balance (docs/ENGINEERING-RULES.md §2:
 * "balances are read via {@code LedgerPort.lookupAccount}; never compute a participant balance by
 * summing rows in PostgreSQL").
 *
 * <p><b>The account ids are re-derived here, never remembered from the write.</b> {@code
 * AllotmentService} opened these accounts under its own {@link AccountIdDeriver}; this class
 * derives the same ids from the same public inputs, through the same {@link ParticipantScope}
 * {@code balance-api} uses. A drift between the two derivations does not fail loudly — it reads
 * accounts that do not exist and reports every participant as holding nothing — so deriving rather
 * than capturing is the point, not an inconvenience.
 *
 * <p>Counterpart to {@link QueryApi}, which is the same platform seen over HTTP.
 */
final class LedgerView {

  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;

  LedgerView(PlatformStack stack) {
    // allotment-job's beans: the service that opened these accounts, so its namespace is the
    // one they exist under. Every context in this stack shares it (see PlatformStack).
    this.ledger = stack.allotmentJobBean(LedgerPort.class);
    this.accountIds = stack.allotmentJobBean(AccountIdDeriver.class);
  }

  /** What the participant holds outright, in the fund their contribution bought into. */
  Units vestedUnits(Scenario scenario) {
    return units(holding(scenario, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));
  }

  /** What the participant holds subject to the plan's vesting schedule. */
  Units unvestedUnits(Scenario scenario) {
    return units(holding(scenario, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED));
  }

  /** Everything the participant holds in that fund, vested and not — their whole position. */
  Units unitsHeld(Scenario scenario) {
    return Units.of(vestedUnits(scenario).scaled() + unvestedUnits(scenario).scaled());
  }

  /** The fund's issuance account: every unit it has ever issued, less any redeemed. */
  Units unitsInIssue(FundId fundId) {
    return units(fundAccount(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId));
  }

  /** The pooled cash the fund holds against those units. */
  Money fundCash(FundId fundId) {
    return cash(fundAccount(LedgerCodes.AccountCode.FUND_CASH, fundId));
  }

  /** The cash waiting between the employer's collections and the fund. Zero between runs. */
  Money allotmentSuspense(FundId fundId) {
    return cash(fundAccount(LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE, fundId));
  }

  /** The employer's received-but-not-yet-allotted cash. Zero once every batch is allotted. */
  Money employerCollections(OrganizationId organizationId) {
    return cash(
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()));
  }

  private AccountId holding(Scenario scenario, LedgerCodes.AccountCode accountCode) {
    ParticipantScope scope =
        new ParticipantScope(scenario.organizationId(), scenario.participantInternalRef());
    return accountIds.derive(accountCode, scope.forFund(scenario.fundId()));
  }

  private AccountId fundAccount(LedgerCodes.AccountCode accountCode, FundId fundId) {
    return accountIds.derive(accountCode, fundId.toString());
  }

  /**
   * An account this module's own scenarios opened. Absent means the allotment never reached the
   * ledger, which is a failure worth naming here rather than reporting as a zero balance three
   * assertions later.
   */
  private LedgerAccount lookup(AccountId id) {
    return ledger
        .lookupAccount(id)
        .orElseThrow(() -> new AssertionError("no ledger account " + id + " — nothing opened it"));
  }

  private Units units(AccountId id) {
    if (lookup(id) instanceof LedgerAccount.Unit unit) {
      return unit.balance();
    }
    throw new AssertionError(id + " is on the cash ledger, not a unit ledger");
  }

  private Money cash(AccountId id) {
    if (lookup(id) instanceof LedgerAccount.Cash account) {
      return account.balance();
    }
    throw new AssertionError(id + " is on a unit ledger, not the cash ledger");
  }

  /** Zero pesos — what a suspense account holds when nothing is in flight through it. */
  static Money noCash() {
    return Money.of(0L, CurrencyCode.MXN);
  }
}
