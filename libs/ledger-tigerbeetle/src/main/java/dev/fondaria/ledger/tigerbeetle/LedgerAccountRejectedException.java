package dev.fondaria.ledger.tigerbeetle;

import dev.fondaria.ledger.AccountId;

/**
 * TigerBeetle refused to create an account for a reason that is not a normal, expected outcome of
 * {@code createAccount} (those are {@code Created} and {@code Exists}/{@code ExistsWithDifferent*},
 * both treated as success — see {@link TigerBeetleLedgerAdapter}). Anything else — a reserved
 * field, a zero id, a mismatched ledger — is a caller bug, not a business rejection, so it is not
 * modeled in {@code LedgerPort} and surfaces as this unchecked exception instead.
 */
public final class LedgerAccountRejectedException extends RuntimeException {

  public LedgerAccountRejectedException(AccountId id, String reason) {
    super("Account creation rejected for " + id + ": " + reason);
  }
}
