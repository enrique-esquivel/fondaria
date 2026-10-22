package dev.fondaria.ledger;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * A ledger account as read back through {@link LedgerPort}. Sealed on the two kinds of quantity a
 * ledger account can hold ({@code ledger = 484} cash vs. a per-fund unit ledger), so a unit
 * account's balance can never be read as {@link Money}, nor a cash account's balance as {@link
 * Units} — the compiler enforces the separation, not a runtime check.
 */
public sealed interface LedgerAccount permits LedgerAccount.Cash, LedgerAccount.Unit {

  AccountId id();

  LedgerCodes.AccountCode accountCode();

  boolean debitsMustNotExceedCredits();

  /**
   * A cash-ledger account ({@code ledger = 484}). Always MXN — v1 has one cash ledger and it is
   * MXN's ISO 4217 numeric code, enforced below rather than left to whatever currency the caller
   * happened to pass in {@code balance}.
   */
  record Cash(
      AccountId id,
      LedgerCodes.AccountCode accountCode,
      boolean debitsMustNotExceedCredits,
      Money balance)
      implements LedgerAccount {

    public Cash {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(accountCode, "accountCode must not be null");
      Objects.requireNonNull(balance, "balance must not be null");
      LedgerCodes.requireCashLedgerCurrency(balance.currency());
    }
  }

  /** A unit-ledger account (one ledger id per fund): issuance or a participant's holdings. */
  record Unit(
      AccountId id,
      LedgerCodes.AccountCode accountCode,
      boolean debitsMustNotExceedCredits,
      long fundLedgerId,
      Units balance)
      implements LedgerAccount {

    public Unit {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(accountCode, "accountCode must not be null");
      Objects.requireNonNull(balance, "balance must not be null");
    }
  }
}
