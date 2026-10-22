package dev.fondaria.ledger;

import dev.fondaria.common.CurrencyCode;

/**
 * Account and transfer codes for the fund ledger. See {@code docs/chart-of-accounts.md} for the
 * full account/transfer documentation and worked examples — this class is the single source of
 * truth for the numeric codes; the document must stay in sync with it.
 */
public final class LedgerCodes {

  private LedgerCodes() {}

  /**
   * The single MXN cash ledger id — {@code 484}, MXN's ISO 4217 numeric code. Every fund's unit
   * ledger id (from {@code 1001}) is per-fund and assigned at fund creation, so there is no
   * equivalent constant for those.
   */
  public static final long CASH_LEDGER_ID = 484L;

  /**
   * v1 is MXN-only (ADR-0004): every {@code Money} value on the cash ledger must carry {@link
   * CurrencyCode#MXN}. Shared by the {@code Cash} variants of {@link LedgerAccount}, {@link
   * LedgerTransfer} and {@link CreateTransferCommand} so a wrong-currency {@code Money} can never
   * be attached to a cash-ledger domain object, even though {@code Money} itself is
   * currency-generic.
   */
  static void requireCashLedgerCurrency(CurrencyCode currency) {
    if (currency != CurrencyCode.MXN) {
      throw new IllegalArgumentException(
          "Cash ledger ("
              + CASH_LEDGER_ID
              + ") balances must be denominated in MXN, got: "
              + currency);
    }
  }

  /** Cash ledger ({@code 484}) and per-fund unit ledger ({@code 1001+}) account roles. */
  public enum AccountCode {
    // Cash ledger (484, MXN cents)
    BANK_CLEARING(1, false),
    EMPLOYER_COLLECTIONS(2, false),
    ALLOTMENT_SUSPENSE(3, false),
    REDEMPTION_SUSPENSE(4, false),
    FUND_CASH(5, false),
    FEE_INCOME(6, false),
    ISR_WITHHOLDING_PAYABLE(7, false),
    PAYOUT_CLEARING(8, false),
    ROUNDING_RESIDUE(9, false),
    OPENING_BALANCE_EQUITY(10, false),

    // Unit ledgers (one per fund, scale 1e-8)
    UNITS_IN_ISSUE(101, false),
    PARTICIPANT_HOLDINGS_VESTED(102, true),
    PARTICIPANT_HOLDINGS_UNVESTED(103, true),
    UNIT_ROUNDING(104, false);

    private final int code;
    private final boolean debitsMustNotExceedCredits;

    AccountCode(int code, boolean debitsMustNotExceedCredits) {
      this.code = code;
      this.debitsMustNotExceedCredits = debitsMustNotExceedCredits;
    }

    public int code() {
      return code;
    }

    /** Whether TigerBeetle's {@code debits_must_not_exceed_credits} flag must be set. */
    public boolean debitsMustNotExceedCredits() {
      return debitsMustNotExceedCredits;
    }
  }

  /** Transfer codes for every money and unit movement the ledger domain performs. */
  public enum TransferCode {
    CASH_RECEIPT(1),
    CONTRIBUTION_ALLOTMENT(2),
    VESTING(3),
    REDEMPTION(4),
    FUND_SWITCH(5),
    FEE_CHARGE(6),
    TAX_WITHHOLDING(7),
    CORRECTION(8),
    NAV_ERROR_COMPENSATION(9),
    OPENING_BALANCE(10);

    private final int code;

    TransferCode(int code) {
      this.code = code;
    }

    public int code() {
      return code;
    }
  }
}
