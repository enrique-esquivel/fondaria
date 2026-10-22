package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@code reference.fund_nav} holding only live rows, one per {@code (fund, date)}.
 *
 * <p>The two write operations throw, exactly as the production {@code ReadOnlyFundNavRepository}
 * does: nothing on a read path may strike or correct a price. {@link #findOriginal} throws too —
 * more strongly than production, so that a service reaching for the pre-correction price fails the
 * test rather than quietly serving a number already known to be wrong.
 */
final class FakeFundNavRepository implements FundNavRepository {

  private final Map<String, FundNav> live = new LinkedHashMap<>();

  /** A price nobody has corrected. */
  FakeFundNavRepository struck(FundId fundId, LocalDate date, long navScaled, long navVersionId) {
    live.put(key(fundId, date), nav(fundId, date, navScaled, navVersionId, null));
    return this;
  }

  /** A price that supersedes an earlier one for the same date (Task 20.5). */
  FakeFundNavRepository corrected(
      FundId fundId, LocalDate date, long navScaled, long navVersionId, long supersedesId) {
    live.put(key(fundId, date), nav(fundId, date, navScaled, navVersionId, supersedesId));
    return this;
  }

  @Override
  public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
    return Optional.ofNullable(live.get(key(fundId, valuationDate)));
  }

  @Override
  public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
    throw new UnsupportedOperationException(
        "a historical balance must resolve the live price, never the superseded original");
  }

  @Override
  public Optional<FundNav> findLatestLive(FundId fundId) {
    throw new UnsupportedOperationException(
        "a historical balance prices its own date, never the fund's most recent one");
  }

  @Override
  public List<FundNav> findLatestLivePerFund() {
    return new ArrayList<>(live.values());
  }

  @Override
  public FundNav strike(FundNav candidate) {
    throw new UnsupportedOperationException("balance-api never strikes a NAV");
  }

  @Override
  public FundNav correct(FundNav candidate) {
    throw new UnsupportedOperationException("balance-api never corrects a NAV");
  }

  private static FundNav nav(
      FundId fundId, LocalDate date, long navScaled, long navVersionId, Long supersedesId) {
    return new FundNav(
        navVersionId,
        fundId,
        date,
        Nav.of(navScaled, CurrencyCode.MXN),
        Units.of(1_000_00000000L),
        "TEST",
        supersedesId,
        supersedesId == null ? null : "restated by the administrator",
        Instant.parse("2026-03-02T06:00:00Z"));
  }

  private static String key(FundId fundId, LocalDate date) {
    return fundId + "@" + date;
  }
}
