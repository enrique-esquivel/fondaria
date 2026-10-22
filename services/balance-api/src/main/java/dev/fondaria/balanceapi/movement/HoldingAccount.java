package dev.fondaria.balanceapi.movement;

import dev.fondaria.ledger.LedgerCodes;

/**
 * Which of a participant's two per-fund unit accounts a movement posted to — {@code HoldingAccount}
 * in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>Kept separate rather than netted, for the same reason the balance reports vested and unvested
 * separately (ADR-0004): a {@code VESTING} transfer moves units from one to the other and shows as
 * two movements, a debit here and a credit there. Netting them to nothing would hide the one event
 * the participant most wants to see.
 */
public enum HoldingAccount {
  VESTED(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED),
  UNVESTED(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED);

  private final LedgerCodes.AccountCode accountCode;

  HoldingAccount(LedgerCodes.AccountCode accountCode) {
    this.accountCode = accountCode;
  }

  /** The chart-of-accounts code this holding is derived under. */
  public LedgerCodes.AccountCode accountCode() {
    return accountCode;
  }
}
