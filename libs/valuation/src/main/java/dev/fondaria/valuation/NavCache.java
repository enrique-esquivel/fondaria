package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import java.util.List;
import java.util.Optional;

/**
 * The current NAV per fund, held in memory in every service that needs to value a holding (Task
 * 20.6, replacing the struck Task 5.6).
 *
 * <p><b>It is a handful of numbers — one per fund — so there is no coordination here and no
 * distributed cache.</b> Each instance holds its own copy and converges on the authoritative rows
 * in {@code reference.fund_nav} within a configured interval. Two instances briefly disagreeing
 * about a price is acceptable in a way that two instances disagreeing about a balance never would
 * be: a balance comes from the ledger, and this is the multiplier applied to it.
 *
 * <p><b>An interface, and in {@code libs/} rather than in the publishing service</b>, because every
 * service values holdings but only valuation-api strikes prices. A service that reads NAV depends
 * on this contract and an implementation; it does not depend on the service that writes the table.
 */
public interface NavCache {

  /**
   * The live price for {@code fundId}, or empty when the fund has never been struck — never a zero,
   * which would be a different and misleading claim (the same rule Phase 6 applies to a balance
   * before a participant's first contribution).
   *
   * <p>A miss falls through to the authoritative store rather than reporting absence, so a freshly
   * started instance serves a correct price before its first sweep has run.
   */
  Optional<CachedNav> current(FundId fundId);

  /**
   * Every fund this instance currently holds a live price for, each marked stale or not by the same
   * rule {@link #current(FundId)} applies (Task 6.2).
   *
   * <p><b>This is how a reader discovers which funds exist without asking PostgreSQL.</b> The
   * balance path derives a participant's holding accounts per fund and looks them up in one batched
   * ledger call, which needs the candidate set up front; taking it from here rather than from
   * {@code reference.fund} is what lets {@code GET /balance} keep answering while the database is
   * down. A fund with no price could not be valued anyway, so the set this returns is exactly the
   * set a balance can be computed over.
   *
   * <p><b>No read-through, unlike {@link #current(FundId)}</b> — absence of a fund from this set is
   * not a question that can be asked of the store, only the whole set can be re-read, which is what
   * {@link #refreshAll()} is. So the set lags a first-ever strike by at most the sweep interval.
   * That is not a gap in practice: a participant holds units in a fund only because an allotment
   * priced them, and an allotment requires a NAV that was already struck before it ran.
   */
  List<CachedNav> currentAll();

  /**
   * Re-reads one fund's live NAV from the authoritative store immediately.
   *
   * <p>Called on publication and on supersession — the two events the interval must not be allowed
   * to delay. A correction is the sharper of the two: between striking it and picking it up, this
   * instance is serving a price somebody has already declared wrong.
   */
  void refresh(FundId fundId);

  /**
   * Re-reads every fund's live NAV. The interval sweep, and what makes "within the documented
   * interval" true for an instance that never saw the publication itself.
   *
   * @return how many funds are held after the sweep
   */
  int refreshAll();
}
