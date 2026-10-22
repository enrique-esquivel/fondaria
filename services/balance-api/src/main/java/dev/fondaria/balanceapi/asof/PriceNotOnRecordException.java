package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.FundId;
import java.io.Serial;
import java.util.Objects;

/**
 * The participant held units in a fund on the requested date, but no NAV is on record for that fund
 * on that date.
 *
 * <p><b>The whole request fails; no partial total is served.</b> A response listing only the funds
 * that happened to have a price would understate the participant's holdings with nothing on it
 * saying so — the same reason cash awaiting allotment is omitted rather than estimated (Task 6.2).
 * A figure that is silently missing a fund is worse than no figure.
 *
 * <p><b>Why this is not a fallback to the nearest earlier price.</b> {@code fund_nav} is dense —
 * one row per fund per day (docs/DOMAIN.md) — and the phase document's own query pins {@code
 * valuation_date = ?}. Carrying an earlier day's price forward would invent a valuation nobody
 * struck and would answer a different question from the one asked, which for a historical figure
 * that may be relied on years later is not a convenience but a fabrication.
 *
 * <p>Carries the fund id and nothing else. A fund is a platform object, not a person, so it may be
 * logged so an operator knows which price is missing; the requested date and the participant may
 * not, and neither reaches the response (docs/ENGINEERING-RULES.md §7).
 */
public final class PriceNotOnRecordException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final FundId fundId;

  public PriceNotOnRecordException(FundId fundId) {
    super("no live reference.fund_nav row exists for this fund on the requested date");
    this.fundId = Objects.requireNonNull(fundId, "fundId must not be null");
  }

  /** The fund with no price on the requested date. For a log line, never for a response body. */
  public FundId fundId() {
    return fundId;
  }
}
