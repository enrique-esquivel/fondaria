package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.FundNav;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A price served from {@link NavCache}, with the one thing a caller must not be allowed to overlook
 * attached to it: whether it is stale (Task 20.6).
 *
 * <p><b>Staleness is a field, not a method the caller may forget to call.</b> The requirement is
 * that a stale NAV is "served marked stale, never silently" — so the mark travels with the price
 * rather than sitting behind a predicate on the side. A caller that ignores {@link #stale()} has
 * ignored something visible in its own type; a caller that never called {@code isStale(now)} has
 * merely failed to think of it.
 *
 * <p><b>Age is measured from {@code publishedAt}, not from the valuation date.</b> The scenario
 * this protects against is Task 20.3's — a provider goes quiet, nothing is struck, and every
 * balance in the fund quietly keeps multiplying by an old price. What matters there is how long ago
 * the platform last had a price at all, which is an {@link Instant} subtraction. Measuring in
 * business days from {@code valuationDate} would need a {@code BusinessCalendar} here and would
 * answer a question nobody is asking.
 *
 * <p>Never a balance. This is a price; {@code Money.value(units, nav)} is the only place it becomes
 * pesos (AGENTS.md §2).
 *
 * @param navVersionId the row in {@code reference.fund_nav} this price came from. Carried so a
 *     caller can record <i>which version it used</i> (AGENTS.md §4) — Phase 6 puts it on every
 *     response, Task 21.2 puts it on every allotment.
 * @param stale whether {@code publishedAt} is older than the configured age, computed at read time
 *     against the reading clock — not stored, because it changes without anything being written.
 */
public record CachedNav(
    FundId fundId,
    long navVersionId,
    Nav nav,
    LocalDate valuationDate,
    Instant publishedAt,
    boolean stale) {

  public CachedNav {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    Objects.requireNonNull(publishedAt, "publishedAt must not be null");
  }

  /**
   * Marks {@code row} stale when it was published more than {@code staleAfter} before {@code now}.
   *
   * <p>The comparison is strict: exactly {@code staleAfter} old is not yet stale, so a configured
   * age of 26 hours on a daily NAV does not flip to stale at the instant it becomes a day and two
   * hours old.
   */
  public static CachedNav of(FundNav row, Instant now, Duration staleAfter) {
    Objects.requireNonNull(row, "row must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(staleAfter, "staleAfter must not be null");
    boolean stale = row.publishedAt().plus(staleAfter).isBefore(now);
    return new CachedNav(
        row.fundId(), row.navVersionId(), row.nav(), row.valuationDate(), row.publishedAt(), stale);
  }
}
