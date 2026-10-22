package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@link ProviderAccount}'s comparison for one pass: what the ledger says, against what the
 * statement says.
 */
public record CustodianAccountTie(
    UUID providerAccountId,
    FundId fundId,
    ProviderScopeType scopeType,
    long ledgerUnitsScaled,
    long statementUnitsScaled,
    long pendingTradeUnitsScaled,
    long ledgerCashCents,
    long statementCashCents,
    long pendingTradeCashCents) {

  public CustodianAccountTie {
    Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(scopeType, "scopeType must not be null");
  }

  public long unitDrift() {
    return Math.subtractExact(ledgerUnitsScaled, statementUnitsScaled);
  }

  public long cashDrift() {
    return Math.subtractExact(ledgerCashCents, statementCashCents);
  }

  /** The unit drift left unexplained after the statement's own pending-trade figure. */
  public long unexplainedUnitDrift() {
    return Math.subtractExact(unitDrift(), pendingTradeUnitsScaled);
  }

  public long unexplainedCashDrift() {
    return Math.subtractExact(cashDrift(), pendingTradeCashCents);
  }
}
