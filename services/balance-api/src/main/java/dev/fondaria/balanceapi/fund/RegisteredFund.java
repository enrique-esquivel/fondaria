package dev.fondaria.balanceapi.fund;

import dev.fondaria.common.FundId;
import java.util.Objects;

/**
 * A fund as {@code reference.fund_identity} records it: its own id, and the TigerBeetle ledger its
 * units live on.
 *
 * <p><b>Both halves are needed because the two stores key a fund differently.</b> Everything public
 * — a URL, a response body, {@code reference.fund_nav} — names a fund by its {@link FundId}. The
 * CDC-fed projection does not: {@code projection.participant_units.fund_id} is the <i>unit ledger
 * id</i>, an integer, because that is the only fund key a ledger event carries (Task 5.5). Reading
 * a holding out of the projection therefore needs the ledger id, and reporting it needs the {@code
 * FundId} — so the two travel together rather than being looked up twice.
 *
 * <p>Both are fixed at registration and never change ({@code V10__fund.sql}), which is why a caller
 * may hold this pair for the length of a request without re-resolving it.
 */
public record RegisteredFund(FundId fundId, long unitLedgerId) {

  public RegisteredFund {
    // Deliberately no "unitLedgerId >= 1001" check, though LedgerCodes says unit ledgers start
    // there. This is a read model over a registry it does not write, and reference.fund_identity
    // carries no such constraint of its own: turning one odd stored row into an exception would
    // fail every read for every participant, when the honest consequence of an id no projection
    // row can carry is simply that the fund matches nothing.
    Objects.requireNonNull(fundId, "fundId must not be null");
  }
}
