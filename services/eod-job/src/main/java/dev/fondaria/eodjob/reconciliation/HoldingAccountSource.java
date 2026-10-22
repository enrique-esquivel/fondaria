package dev.fondaria.eodjob.reconciliation;

import java.util.List;
import java.util.Optional;

/**
 * Where the ledger side of the unit tie-out gets its candidate set.
 *
 * <p>{@code LedgerPort} exposes no account enumeration, and deliberately so: every account id on
 * this platform is derived rather than stored, so the ledger is asked for accounts by id and has no
 * "list them" question to answer. Summing every participant's units therefore needs a list of
 * {@link HoldingKey}s to derive ids from, and {@code workflow.allotment} is that list — written by
 * allotment-job at the instant units were issued, and independent of the CDC projection this
 * tie-out compares against.
 *
 * <p>Paged rather than returned whole: at a million participants across several funds the candidate
 * set is millions of triples, and a control that only works while the whole book fits in one heap
 * is not a control.
 */
public interface HoldingAccountSource {

  /**
   * The next page of distinct holdings, in a total order, strictly after {@code after}.
   *
   * @param after the last key of the previous page, or empty for the first page
   * @param limit maximum keys to return; a short page means the last one
   */
  List<HoldingKey> nextPage(Optional<HoldingKey> after, int limit);
}
