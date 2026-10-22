package dev.fondaria.eodjob.snapshot;

import dev.fondaria.eodjob.reconciliation.HoldingKey;
import java.time.Instant;
import java.util.List;

/**
 * The lots a page of positions is built from — what the units held actually cost.
 *
 * <h2>Cost basis is defined here, and the definition matters</h2>
 *
 * <p>A position's cost basis is {@code Σ Money.value(lot units, the NAV that lot was issued at)}
 * over its allotment lots. That is exactly reproducible from stored data years later — {@code
 * workflow.allotment} records the units and the {@code nav_version_id}, both append-only — which is
 * what AGENTS.md §4 requires of any figure a statement will carry.
 *
 * <p><b>It is not identical to the pesos the employer sent, and the gap is known and bounded.</b>
 * Allotment floors units, so a lot's cash was up to one tick more than the units issued are worth
 * at the issuing price. That residue is not lost track of: Task 21.3 records it per run in {@code
 * workflow.allotment_residual}, computed over the run's totals. Recovering the per-lot cash instead
 * would mean reaching into {@code workflow.contribution_batch_row} — contribution-api's staging
 * table, a different service's working state, and one whose rows are not the audit trail of what
 * was issued. The definition here is the one the allotment audit trail can stand behind on its own.
 *
 * <p><b>{@code O-26} (average cost or FIFO) does not block this.</b> Those two disagree about which
 * lots leave on a <i>disposal</i>, and the totals they produce for a position nobody has redeemed
 * from are identical. v1 redeems nothing (Phase 22 is unbuilt), so the sum over every lot is the
 * answer under either, and the decision can be taken when it first has consequences.
 *
 * <h2>Why the bound is on when the lot was posted, and not on its valuation date</h2>
 *
 * <p>A lot belongs to a snapshot exactly when the units it issued do, or the file reports a cost
 * with nothing behind it. Units come from {@code projection.participant_units}, bounded on {@code
 * effective_date} — and {@code effective_date} is <b>the business date of the ledger commit</b>
 * ({@code ParticipantUnitsProjection}: {@code businessDateOf(entry timestamp)}), a v1
 * simplification of {@code O-4}'s value-date model. So the matching bound on a lot is the business
 * date of {@code workflow.allotment.allotted_at}, which allotment-job writes as it posts the chain
 * — the same event, moments apart.
 *
 * <p><b>The lot's NAV valuation date is emphatically not that date</b>, and an earlier draft of
 * this class bounded on it. {@code AllotmentService} prices a batch at the NAV struck for its
 * <i>dealing date</i> — the value date rolled forward by the plan's dealing lag — so a batch dealt
 * on Monday and posted on Tuesday carries a Monday NAV. Bounding lots on the valuation date
 * therefore put Monday's lot into Monday's cost basis while its units, effective Tuesday, were not
 * there: a fabricated loss of the whole lot's value on a position that had not moved, and a Monday
 * file whose hash changed depending on whether it was built before or after allotment-job caught
 * up.
 *
 * <p>When {@code O-4}'s value-date model reaches the projection, both bounds move together, because
 * both are "the date the projection filed this movement under".
 */
public interface CostBasisSource {

  /**
   * Every lot belonging to holdings in the inclusive range {@code [from, to]} that was posted
   * before {@code postedBefore}.
   *
   * <p>A range of holdings rather than a list of keys because a page of the snapshot's keyset scan
   * <i>is</i> a contiguous range in {@code (organization_id, participant_internal_ref, fund_id)}
   * order — the same order {@code allotment_by_holding} (V25) is built in — so the read is one
   * index range scan over exactly the page, with no per-key round trip and no
   * thousands-of-parameters statement.
   *
   * <p>{@code postedBefore} is a coarse instant bound, not the precise cut: the caller applies
   * {@code BusinessCalendar} to each lot's {@code allottedAt} to decide membership, because which
   * business date an instant falls in is the calendar's to answer and not a query's. Passing an
   * instant safely after the business date's cut-off keeps the scan bounded without moving that
   * decision into SQL.
   */
  List<AllotmentLot> lotsFor(HoldingKey from, HoldingKey to, Instant postedBefore);
}
