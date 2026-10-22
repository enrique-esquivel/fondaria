package dev.fondaria.balanceapi.support;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.NavCache;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link NavCache} whose contents a test sets directly.
 *
 * <p>A test double, not a second implementation — the ban in AGENTS.md is on a second production
 * {@code LedgerPort}, and {@code InMemoryNavCache}'s own behaviour is covered by its own tests.
 * What is being tested here is what {@code BalanceService} does with a price, including a stale
 * one, and driving that through a real cache would mean manipulating a clock to produce staleness
 * rather than simply stating it.
 */
public final class FakeNavCache implements NavCache {

  private final Map<FundId, CachedNav> prices = new LinkedHashMap<>();

  /** Adds a live (not stale) price for {@code fundId}. */
  public FakeNavCache with(FundId fundId, long navScaled, long navVersionId) {
    return with(fundId, navScaled, navVersionId, false);
  }

  public FakeNavCache with(FundId fundId, long navScaled, long navVersionId, boolean stale) {
    prices.put(
        fundId,
        new CachedNav(
            fundId,
            navVersionId,
            Nav.of(navScaled, CurrencyCode.MXN),
            LocalDate.of(2026, 8, 31),
            Instant.parse("2026-08-31T18:00:00Z"),
            stale));
    return this;
  }

  @Override
  public Optional<CachedNav> current(FundId fundId) {
    return Optional.ofNullable(prices.get(fundId));
  }

  @Override
  public List<CachedNav> currentAll() {
    return List.copyOf(prices.values());
  }

  @Override
  public void refresh(FundId fundId) {
    // Nothing to converge on: a test states the prices it wants.
  }

  @Override
  public int refreshAll() {
    return prices.size();
  }
}
