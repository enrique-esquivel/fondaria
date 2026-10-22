package dev.fondaria.contribution.cashreceipt;

import java.io.Serial;

/**
 * The ledger refused the movement. Nothing moved, and the receipt is {@link
 * CashReceiptStatus#REJECTED}.
 *
 * <p>A <b>business</b> rejection, so it is cached and replayed: a retry gets the same 422 without
 * touching the ledger again. Contrast {@link CashReceiptUnconfirmedException}, which is
 * infrastructure and is never cached — caching that would turn a transient outage into a permanent
 * refusal of a wire that really did arrive.
 */
public final class CashReceiptRejectedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient CashReceipt receipt;

  public CashReceiptRejectedException(CashReceipt receipt) {
    super("The ledger refused this cash receipt");
    this.receipt = receipt;
  }

  public CashReceipt receipt() {
    return receipt;
  }

  /** Never null: the database constrains a {@code REJECTED} row to carry one. */
  public String reason() {
    return receipt.rejectionReason().orElseThrow();
  }
}
