package dev.fondaria.contribution.batch;

import dev.fondaria.ledger.LedgerLimits;
import java.util.ArrayList;
import java.util.List;

/**
 * Divides a validated file's rows into parts, each small enough that allotting it is one linked
 * chain (ADR-0017).
 *
 * <h2>What one chain costs</h2>
 *
 * <p>{@code AllotmentService} posts, for one batch, two cash legs — {@code EMPLOYER_COLLECTIONS →
 * ALLOTMENT_SUSPENSE → FUND_CASH} — plus one unit leg for every <i>non-zero source</i> on every
 * row. So the budget a part must fit inside is {@link LedgerLimits#MAX_LINKED_CHAIN_LENGTH} minus
 * those two cash legs, and the thing being counted is legs, not rows.
 *
 * <p><b>That distinction is the whole reason this class counts anything.</b> {@code TASK-LOG.md}
 * step 40 derived "roughly 4,093 rows" from two legs per row, which is what a file with an employee
 * and an employer lot on every row costs. {@link ContributionSource} has three members: a part of
 * 4,093 rows that also carries voluntary contributions is 12,281 transfers, and would hit exactly
 * the wall this design exists to remove. Counting each row's own legs is what makes the guarantee
 * hold for every file rather than for the common one.
 *
 * <p>The row cap is kept as well, and applies alongside the leg budget — a part closes on whichever
 * bound it reaches first. It is not redundant: it is what stops an employee-only file, which costs
 * one leg per row, from producing parts of 8,187 rows.
 *
 * <h2>Deterministic, because a re-run must not re-partition</h2>
 *
 * <p>Staging is re-runnable — a crash mid-file leaves it to be redone — and rows are idempotent on
 * {@code (organizationId, batchId, participantInternalRef)}, which only protects a row that lands
 * in the <i>same</i> part both times. A partition that moved a participant from part 3 to part 4
 * would defeat that key and stage them twice, which is a doubled contribution. So this is a pure
 * function of the row order the parser produced and the two bounds, with no clock, no id and no map
 * iteration in it; {@code ContributionStagingService} additionally writes a whole file's parts in
 * one transaction, so a bound that changed between attempts cannot leave two partitions
 * half-applied.
 */
final class BatchPartitioner {

  /**
   * The unit legs one part may cost: the whole chain, less the two cash legs every batch posts
   * regardless of how many rows it has.
   */
  static final int MAX_UNIT_LEGS_PER_PART = LedgerLimits.MAX_LINKED_CHAIN_LENGTH - 2;

  private BatchPartitioner() {}

  /**
   * @param rows the file's rows, in the order the parser produced them
   * @param maxRowsPerPart the row bound, applied alongside {@link #MAX_UNIT_LEGS_PER_PART}
   * @return one list per part, in file order, together holding every row exactly once. A file that
   *     fits in one chain returns a single part — which is the ordinary case, and the case in which
   *     nothing downstream creates a part row at all.
   */
  static List<List<ContributionFile.ParticipantRow>> partition(
      List<ContributionFile.ParticipantRow> rows, int maxRowsPerPart) {
    if (maxRowsPerPart <= 0) {
      throw new IllegalArgumentException("maxRowsPerPart must be positive");
    }

    List<List<ContributionFile.ParticipantRow>> parts = new ArrayList<>();
    List<ContributionFile.ParticipantRow> current = new ArrayList<>();
    int legsInCurrent = 0;

    for (ContributionFile.ParticipantRow row : rows) {
      int legs = unitLegsOf(row);
      boolean wouldOverflow =
          current.size() + 1 > maxRowsPerPart || legsInCurrent + legs > MAX_UNIT_LEGS_PER_PART;
      if (wouldOverflow && !current.isEmpty()) {
        parts.add(List.copyOf(current));
        current = new ArrayList<>();
        legsInCurrent = 0;
      }
      current.add(row);
      legsInCurrent += legs;
    }
    if (!current.isEmpty()) {
      parts.add(List.copyOf(current));
    }
    return List.copyOf(parts);
  }

  /**
   * One leg per source this row actually contributes from — the same test {@code
   * AllotmentService}'s own loop applies ({@code amountMinorUnits <= 0} posts nothing).
   *
   * <p>A single row can never exceed the budget on its own: {@link ContributionSource} has three
   * members and the budget is in the thousands. A row whose amounts are all zero never parses, so
   * this is at least one for every row that reaches here — and even if it were zero, an empty part
   * could not be produced, because a part is only closed when a row is about to be added to it.
   *
   * <p>Deliberately an over-estimate in one direction only: allotment skips a leg whose amount is
   * too small to buy a single unit tick at the day's NAV, so the chain that is eventually posted
   * can be shorter than what was budgeted here, never longer. Sizing against a price nobody has
   * struck yet would be the alternative, and it would make a file's partition depend on a number
   * that does not exist when the file is staged.
   */
  private static int unitLegsOf(ContributionFile.ParticipantRow row) {
    int legs = 0;
    for (ContributionSource source : ContributionSource.values()) {
      if (row.amountOf(source) > 0) {
        legs++;
      }
    }
    return legs;
  }
}
