package dev.fondaria.ledger;

import dev.fondaria.common.NameBasedUuid;
import java.util.Objects;
import java.util.UUID;

/**
 * Derives ledger account ids deterministically:
 *
 * <pre>account_id = UUIDv5(ledgerNamespace, accountCode + ":" + scope)</pre>
 *
 * <p>The chart of accounts (`docs/chart-of-accounts.md`) has two kinds of account. Platform and
 * per-fund singletons — {@code BANK_CLEARING}, {@code FEE_INCOME} — have exactly one instance for a
 * given scope. Per-holder accounts — {@code EMPLOYER_COLLECTIONS}, one per organization — have one
 * instance per scope value. Both are derived here, so an account's id is a pure function of what
 * the account <i>is</i> rather than of a registry row that has to be looked up, kept in sync, and
 * backed up.
 *
 * <p>That means opening an account is idempotent by construction: {@link
 * LedgerPort#createAccount(CreateAccountCommand)} with a derived id either creates it or reports it
 * already exists, and re-provisioning the same organization is a no-op rather than a second
 * account. It also means no read is needed on the write path to find out where money goes.
 *
 * <p><b>The scope must be an identifier that cannot drift.</b> Deriving from a free-text name gives
 * {@code "Acme"} and {@code "acme"} two different accounts and splits one employer's money across
 * both, with nothing to detect it — so callers derive from a canonical identifier (see {@code
 * TaxIdentifier#derivationKey()}), never from user-entered text.
 *
 * <p>The namespace is a UUID from configuration, never hardcoded, and must be stable for the life
 * of the ledger: changing it re-derives every account id and strands every existing balance under
 * ids nothing will look up again.
 */
public final class AccountIdDeriver {

  private final UUID ledgerNamespace;

  public AccountIdDeriver(UUID ledgerNamespace) {
    this.ledgerNamespace = Objects.requireNonNull(ledgerNamespace, "ledgerNamespace");
  }

  /**
   * A singleton account — one instance platform-wide, such as {@code BANK_CLEARING}. Distinct from
   * {@link #derive(LedgerCodes.AccountCode, String)} with an empty scope: the two produce different
   * ids on purpose, so a singleton can never collide with a scoped account whose scope happens to
   * be blank.
   */
  public AccountId derive(LedgerCodes.AccountCode accountCode) {
    Objects.requireNonNull(accountCode, "accountCode");
    return toAccountId(NameBasedUuid.v5(ledgerNamespace, accountCode.name()));
  }

  /**
   * A scoped account — one instance per holder, such as {@code EMPLOYER_COLLECTIONS} per
   * organization.
   *
   * @param scope a canonical, stable identifier for the holder; never user-entered display text
   */
  public AccountId derive(LedgerCodes.AccountCode accountCode, String scope) {
    Objects.requireNonNull(accountCode, "accountCode");
    Objects.requireNonNull(scope, "scope");
    if (scope.isBlank()) {
      throw new IllegalArgumentException(
          "scope must not be blank for a scoped account; use derive(accountCode) for a singleton");
    }
    return toAccountId(NameBasedUuid.v5(ledgerNamespace, accountCode.name() + ":" + scope));
  }

  /** Same byte order as {@link TransferIdDeriver#toTransferId(UUID)} — a direct field copy. */
  public static AccountId toAccountId(UUID uuid) {
    Objects.requireNonNull(uuid, "uuid");
    return AccountId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  public static UUID toUuid(AccountId accountId) {
    Objects.requireNonNull(accountId, "accountId");
    return new UUID(accountId.mostSignificantBits(), accountId.leastSignificantBits());
  }
}
