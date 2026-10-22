package dev.fondaria.balanceapi.fund;

import dev.fondaria.common.FundId;
import java.util.List;

/**
 * Every fund the platform has ever registered.
 *
 * <p><b>Why the historical reads need this and the current balance does not.</b> Task 6.2 discovers
 * funds from {@code NavCache.currentAll()} — the price set — which is what keeps PostgreSQL off the
 * balance path entirely. A history or an as-of holding must not be discovered that way: a fund
 * whose price has not yet been struck on this instance, or which is closed and no longer priced,
 * still has movements and still had holdings, and pricing has nothing to do with what happened. So
 * the candidate set comes from the registry, and those endpoints read PostgreSQL anyway to answer
 * the question at all.
 *
 * <p>Read-only and deliberately narrower than {@code FundRepository}: this service registers no
 * fund, publishes no version and strikes no NAV.
 */
public interface FundDirectory {

  /**
   * Every registered fund with its unit ledger id, in no particular order. Empty when none is
   * registered — never an error; a platform with no funds has no movements and no holdings to
   * report either.
   *
   * <p><b>The unit ledger id is carried rather than dropped</b> because {@code
   * projection.participant_units} keys a fund by it (Task 6.4). See {@link RegisteredFund}.
   */
  List<RegisteredFund> listFunds();

  /** Just the ids, for a caller that never touches the projection's own fund key. */
  default List<FundId> listFundIds() {
    return listFunds().stream().map(RegisteredFund::fundId).toList();
  }
}
