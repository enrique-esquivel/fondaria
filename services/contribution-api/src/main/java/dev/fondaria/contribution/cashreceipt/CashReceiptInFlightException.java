package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import java.io.Serial;

/**
 * Another request holds this receipt's claim right now, so this one cannot tell whether the
 * movement has happened.
 *
 * <p>Answered with 409. The claim is a row lock on {@code workflow.cash_receipt} taken with {@code
 * NOWAIT}, held across the ledger post — <b>not</b> a Redis marker. That matters: the design's
 * original {@code SETNX} in-flight marker (docs/DESIGN-DECISIONS.md) makes concurrency behaviour
 * depend on a cache, and this endpoint's contract is that Redis can be stopped without changing
 * what it does.
 *
 * <p>It is a conflict, not a rejection, and nothing is cached for it: the correct client response
 * is to retry, which will then either replay the winner's result or converge on it.
 */
public final class CashReceiptInFlightException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final TransferId transferId;

  public CashReceiptInFlightException(TransferId transferId, Throwable cause) {
    super("A request for this cash receipt is already in flight", cause);
    this.transferId = transferId;
  }

  public TransferId transferId() {
    return transferId;
  }
}
