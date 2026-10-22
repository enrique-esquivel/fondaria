package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import java.io.Serial;

/**
 * The ledger post did not complete, and this service does not know whether it reached the cluster.
 * The receipt stays {@link CashReceiptStatus#UNCONFIRMED}.
 *
 * <p>This is the state ADR-0011's write order exists to produce, and it is a good state: the row
 * says an operator asserted a wire and the movement is unconfirmed, which is reconcilable. Any
 * retry converges, because the transfer id is derived — if the first attempt did reach the ledger,
 * the retry sees it already exists and moves the row to {@code POSTED}; if it did not, the retry
 * posts it.
 *
 * <p>Answered with 503 and <b>never cached</b>. Caching an infrastructure failure would turn a blip
 * into a permanent refusal.
 */
public final class CashReceiptUnconfirmedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final TransferId transferId;

  public CashReceiptUnconfirmedException(TransferId transferId, Throwable cause) {
    super("The cash receipt was recorded but its ledger movement is unconfirmed", cause);
    this.transferId = transferId;
  }

  public TransferId transferId() {
    return transferId;
  }
}
