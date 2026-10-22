package dev.fondaria.contribution.cashreceipt;

import java.util.Objects;

/**
 * What happened to a cash-receipt submission, for a caller that needs to distinguish "this posted
 * the money" from "this was the same request again".
 *
 * @param disposition how the receipt reached its current state on <b>this</b> call
 */
public record CashReceiptOutcome(CashReceipt receipt, Disposition disposition) {

  public CashReceiptOutcome {
    Objects.requireNonNull(receipt, "receipt must not be null");
    Objects.requireNonNull(disposition, "disposition must not be null");
  }

  public enum Disposition {
    /**
     * This call posted the {@code CASH_RECEIPT} transfer. Exactly one submission ever sees this.
     */
    POSTED,

    /**
     * The movement already existed and this call did not make it. Covers an exact retry, a request
     * that crossed with an earlier one, and the repair of a receipt left {@code UNCONFIRMED} by a
     * crash — all three are the same fact to a caller: the money moved once, not now.
     */
    REPLAYED
  }
}
