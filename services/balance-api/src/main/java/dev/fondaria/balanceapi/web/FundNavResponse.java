package dev.fondaria.balanceapi.web;

import dev.fondaria.valuation.CachedNav;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The price behind one fund's figures, and everything needed to trace it to a {@code
 * reference.fund_nav} row — {@code FundNavComponent} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>{@code navVersionId} is why this is on the response at all.</b> docs/ENGINEERING-RULES.md
 * §4 requires every value to record which NAV version produced it; carried here, a later correction
 * (Task 20.5) can identify exactly what a participant was shown and when.
 *
 * <p><b>One per fund, not one per response.</b> A participant holding two funds is priced by two
 * rows at once, and a single top-level version id could not name both.
 *
 * <p>{@code navScaled} is a plain JSON integer at scale 1e-8 — never a decimal, never a float
 * (docs/ENGINEERING-RULES.md §4).
 */
public record FundNavResponse(
    String fundId,
    long navScaled,
    long navVersionId,
    LocalDate valuationDate,
    Instant publishedAt,
    boolean stale) {

  public static FundNavResponse from(CachedNav nav) {
    return new FundNavResponse(
        nav.fundId().toString(),
        nav.nav().scaled(),
        nav.navVersionId(),
        nav.valuationDate(),
        nav.publishedAt(),
        nav.stale());
  }
}
