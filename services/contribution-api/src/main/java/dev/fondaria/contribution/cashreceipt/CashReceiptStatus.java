package dev.fondaria.contribution.cashreceipt;

import java.util.Locale;

/**
 * Where one manually reconciled wire stands (ADR-0010).
 *
 * <p>The lifecycle is the point of the table, not decoration on it. ADR-0011 requires that a crash
 * between the PostgreSQL write and the ledger post leave "a row whose status says the movement is
 * unconfirmed", and {@link #UNCONFIRMED} is that row. It is written first, on purpose, so the
 * failure mode is a receipt that may not have moved money — reconcilable — rather than money that
 * moved with nothing recording why.
 */
public enum CashReceiptStatus {

  /**
   * The operator's assertion is recorded; the ledger movement is not confirmed. Every receipt
   * passes through this state, and a crash mid-post leaves one here. Any retry of the same
   * (organization, idempotency key) converges from it, because the transfer id is derived and the
   * ledger rejects a second attempt itself.
   */
  UNCONFIRMED,

  /** The {@code CASH_RECEIPT} transfer exists in the ledger. The money is in the ledger's hands. */
  POSTED,

  /**
   * The ledger refused the movement, and nothing moved. Terminal for this idempotency key: a
   * corrected entry is a new receipt under a new key, never an edit of this row.
   */
  REJECTED;

  public static CashReceiptStatus parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("status must be one of UNCONFIRMED, POSTED, REJECTED");
    }
  }
}
