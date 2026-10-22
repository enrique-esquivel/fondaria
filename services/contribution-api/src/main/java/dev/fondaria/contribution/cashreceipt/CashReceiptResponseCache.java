package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import java.util.Optional;

/**
 * A 24-hour response cache for cash receipts, and <b>nothing more</b>.
 *
 * <p>Read that literally, because the design of this endpoint depends on it: every method here may
 * fail, return nothing, or be served by a store that has lost every key, and the endpoint must
 * behave identically. Idempotency is the ledger's job — the transfer id is derived, so TigerBeetle
 * refuses the duplicate itself — and "same key, different request" is answered from the fingerprint
 * on {@code workflow.cash_receipt}. This only saves those two stores a round trip.
 *
 * <p>The original design made Redis the idempotency authority via {@code SETNX}, and
 * 01-DECISIONS-TO-DISCUSS.md C3 records why that was withdrawn: a failover losing a few seconds of
 * writes permits a double credit. Caches lose data; this one is allowed to.
 *
 * <p><b>Nothing is computed here.</b> No counter, no balance, no arithmetic on money in Redis, and
 * so no {@code INCRBYFLOAT} (AGENTS.md §4) — one receipt goes in and comes back out verbatim.
 *
 * <p><b>Keyed by the derived transfer id, not by the idempotency key.</b> Idempotency keys are on
 * the never-log list (AGENTS.md §7) and a Redis key is visible to {@code MONITOR} and {@code
 * SLOWLOG}; the transfer id is the opaque derivation of the same thing and identifies the receipt
 * just as precisely.
 *
 * <p><b>Only resolved receipts are cached.</b> An {@code UNCONFIRMED} receipt is a movement whose
 * outcome nobody knows, and caching one would replay that non-answer for a day.
 */
public interface CashReceiptResponseCache {

  /**
   * The cached receipt, or empty — including when the cache is unreachable. An implementation never
   * propagates an infrastructure failure: to this endpoint, a cache that is down and a cache that
   * has never heard of the key are the same thing.
   */
  Optional<CashReceipt> find(TransferId transferId);

  /** Stores a resolved receipt for 24 hours. Failure is swallowed; it costs a round trip later. */
  void store(CashReceipt receipt);
}
