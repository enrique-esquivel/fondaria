package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.Money;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.LedgerCodes;
import java.util.Objects;

/**
 * One cash account's balance, from the ledger and from the projection's own movements.
 *
 * <p>A cash account may legitimately be negative — a suspense account mid-batch, {@code
 * ROUNDING_RESIDUE} either way (chart-of-accounts.md §5) — so nothing here is clamped and {@link
 * Money} carries the sign.
 *
 * @param accountCode kept for the operator, never for the comparison: two derivations of the same
 *     account id must agree regardless of what the account is for
 */
public record CashAccountTie(
    AccountId accountId,
    LedgerCodes.AccountCode accountCode,
    Money ledgerCash,
    Money projectionCash) {

  public CashAccountTie {
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(accountCode, "accountCode must not be null");
    Objects.requireNonNull(ledgerCash, "ledgerCash must not be null");
    Objects.requireNonNull(projectionCash, "projectionCash must not be null");
  }

  public boolean ties() {
    return ledgerCash.equals(projectionCash);
  }
}
