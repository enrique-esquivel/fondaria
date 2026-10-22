package dev.fondaria.ledger;

import dev.fondaria.common.TenantRef;
import java.util.Objects;

/**
 * Instructs {@link LedgerPort#createAccount} to open one account. The {@code
 * debits_must_not_exceed_credits} flag is never passed explicitly — it is intrinsic to the account
 * role and comes from {@link LedgerCodes.AccountCode#debitsMustNotExceedCredits()}, so a caller
 * can't accidentally open a participant holdings account without it.
 *
 * <p>{@link #tenant()} is written to the account's {@code user_data_64} so the CDC stream can be
 * partitioned tenant-first ({@code ADR-0005}, 2026-08-28 amendment). It defaults to {@link
 * TenantRef#NONE}: a pooled, fund-level account ({@code FUND_CASH}, {@code UNITS_IN_ISSUE}, {@code
 * ALLOTMENT_SUSPENSE}) genuinely belongs to no one organization. A per-holder account ({@code
 * EMPLOYER_COLLECTIONS}, {@code PARTICIPANT_HOLDINGS_*}) carries its holder's ref, set with {@link
 * #withTenant}. The ref must be stable for the life of the account — re-provisioning the same
 * holder is a no-op, so a changed ref would be silently ignored by the ledger.
 */
public record CreateAccountCommand(
    AccountId id, long ledgerId, LedgerCodes.AccountCode accountCode, TenantRef tenant) {

  public CreateAccountCommand {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(accountCode, "accountCode must not be null");
    Objects.requireNonNull(tenant, "tenant must not be null");
    if (ledgerId <= 0) {
      throw new IllegalArgumentException("ledgerId must be positive, got: " + ledgerId);
    }
  }

  /** A pooled account, with no tenant attribution. Add one with {@link #withTenant}. */
  public CreateAccountCommand(AccountId id, long ledgerId, LedgerCodes.AccountCode accountCode) {
    this(id, ledgerId, accountCode, TenantRef.NONE);
  }

  /** The same command, attributed to one organization. */
  public CreateAccountCommand withTenant(TenantRef tenant) {
    return new CreateAccountCommand(id, ledgerId, accountCode, tenant);
  }

  public boolean debitsMustNotExceedCredits() {
    return accountCode.debitsMustNotExceedCredits();
  }
}
