package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import java.io.Serial;

/**
 * The same {@code Idempotency-Key} was submitted for a materially different request — a different
 * amount, organization, value date, bank reference or operator.
 *
 * <p>Answered with 422, never by treating it as a retry. Silently accepting it would post the first
 * request's money and return it as though it were the second's, which is the quiet
 * wrong-amount-credited failure this endpoint exists to make impossible (docs/DESIGN-DECISIONS.md).
 *
 * <p>Detected by comparing request fingerprints, not by the ledger's own result. {@code
 * TigerBeetleLedgerAdapter} collapses every {@code ExistsWith*} status — including {@code
 * ExistsWithDifferentAmount} — into a single {@code AlreadyExists}, so the difference is invisible
 * at that layer. The fingerprint on {@code workflow.cash_receipt} answers it instead, which also
 * means it is answered with Redis cold or stopped.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final TransferId transferId;

  public IdempotencyKeyReusedException(TransferId transferId) {
    super("Idempotency key reused with a different request");
    this.transferId = transferId;
  }

  /** Safe to render: an opaque derived value, carrying nothing about the request it came from. */
  public TransferId transferId() {
    return transferId;
  }
}
