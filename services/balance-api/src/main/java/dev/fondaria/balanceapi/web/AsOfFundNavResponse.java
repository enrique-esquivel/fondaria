package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.asof.AsOfPosition;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The price behind one fund's historical figures — {@code FundNavComponent} in {@code
 * contracts/openapi/balance-api.yaml}, in the form the as-of endpoint renders it.
 *
 * <p><b>A separate record from {@link FundNavResponse} because of one field.</b> {@code corrected}
 * is meaningful only here: on the current balance "the price for this date" and "the live price for
 * this date" cannot differ, so the field would be a constant {@code false} on every other endpoint
 * — and the contract says it is present only on this one. Adding it to the shared record would have
 * changed a response Task 6.2 already ships.
 *
 * <p><b>{@code stale} is always {@code false}, and that is not a stub.</b> Staleness on the current
 * balance means "this instance's cached price may have been overtaken" — a property of the cache,
 * measured from {@code publishedAt}. A historical price is read from {@code reference.fund_nav}
 * directly, is final for its valuation date, and cannot be overtaken by anything except a
 * correction, which is what {@code corrected} reports. There is nothing for a reader to distrust,
 * so nothing is marked.
 *
 * <p>{@code navScaled} is a plain JSON integer at scale 1e-8 — never a decimal, never a float
 * (AGENTS.md §4).
 */
public record AsOfFundNavResponse(
    String fundId,
    long navScaled,
    long navVersionId,
    LocalDate valuationDate,
    Instant publishedAt,
    boolean stale,
    boolean corrected) {

  public static AsOfFundNavResponse from(AsOfPosition position) {
    return new AsOfFundNavResponse(
        position.nav().fundId().toString(),
        position.nav().nav().scaled(),
        position.nav().navVersionId(),
        position.nav().valuationDate(),
        position.nav().publishedAt(),
        false,
        position.corrected());
  }
}
