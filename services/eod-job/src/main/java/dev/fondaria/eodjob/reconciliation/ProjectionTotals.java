package dev.fondaria.eodjob.reconciliation;

import java.util.Map;

/**
 * The third derivation, and the cash half of the fourth: what the CDC-fed projection says the book
 * holds.
 *
 * <p><b>This is the one place in the platform that sums a balance out of PostgreSQL, and it is
 * allowed here for the single reason that makes it useful.</b> docs/ENGINEERING-RULES.md §4 forbids
 * computing a participant balance by summing rows — because a balance served to anyone must come
 * from {@code LedgerPort}. Nothing here is served to anyone. These sums exist only to be compared
 * against the ledger's own answer, and a control whose two sides came from the same source would
 * agree with itself no matter what had gone wrong.
 */
public interface ProjectionTotals {

  /**
   * Units the projection holds, per unit ledger id.
   *
   * <p>The projection is <b>sparse</b>: a row exists only when a holding changed (Task 5.5), so the
   * total is a sum over the latest row per {@code (tenant, participant, fund)} and never a sum over
   * every row.
   */
  Map<Long, Long> unitsByFundLedger();

  /**
   * Net cash per account, keyed by {@code AccountId.asUnsignedDecimal()} — credits minus debits
   * over every projected movement on the cash ledger, which is the same direction {@code
   * LedgerAccount.Cash#balance} reports in.
   */
  Map<String, Long> cashByAccount();
}
