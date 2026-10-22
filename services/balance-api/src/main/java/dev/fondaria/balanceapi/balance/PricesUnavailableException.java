package dev.fondaria.balanceapi.balance;

import java.io.Serial;

/**
 * The NAV cache holds no prices at all, so no holding can be valued.
 *
 * <p><b>This exists to stop the worst answer this service could give: a confident zero.</b> Without
 * it, an unprimed or emptied cache produces no candidate funds, therefore no ledger lookups,
 * therefore no positions — and {@code investedValueCents: 0, stale: false, 200 OK}. A participant
 * reads that as "my money is gone" and an operator reads it as a working system, which is exactly
 * the failure {@code BalanceExceptionHandler} promises never to render.
 *
 * <p>An empty price set is never a legitimate "holds nothing": a participant can only hold units
 * because an allotment priced them, which required a struck NAV. So no prices means this instance
 * cannot answer, not that the answer is zero — a {@code 503}, and safe to retry once the sweep has
 * converged.
 *
 * <p>Distinct from a participant who holds nothing in funds that <i>are</i> priced. That is a real
 * zero and is still served as one.
 */
public final class PricesUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PricesUnavailableException() {
    super("The NAV cache holds no prices; no holding can be valued");
  }
}
