package dev.fondaria.valuationapi.support;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.valuationapi.fund.FundDirectory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link FundDirectory} with no database behind it, so what the validator and the ageing report
 * conclude is provable without a container.
 *
 * <p>Resolution mirrors the real implementation's rule — the latest version not after the date
 * asked for — because "resolved as of the valuation's own date" is one of the behaviours under
 * test, not an implementation detail of the SQL.
 */
public final class InMemoryFundDirectory implements FundDirectory {

  private final Map<FundId, List<Fund>> versions = new LinkedHashMap<>();

  /** A fund live from {@code effectiveFrom}, incepted the same day, {@code ACTIVE}. */
  public FundId register(LocalDate effectiveFrom) {
    return register(FundId.of(java.util.UUID.randomUUID()), effectiveFrom, FundStatus.ACTIVE);
  }

  public FundId register(FundId fundId, LocalDate effectiveFrom, FundStatus status) {
    versions
        .computeIfAbsent(fundId, id -> new ArrayList<>())
        .add(
            new Fund(
                fundId,
                effectiveFrom,
                1,
                "Fund " + fundId.value(),
                "CAJA_DE_AHORRO",
                CurrencyCode.MXN,
                Math.abs(fundId.value().getMostSignificantBits() % 100_000) + 1001,
                Units.SCALE,
                effectiveFrom,
                Nav.of(1_000_000_000L, CurrencyCode.MXN),
                status,
                Instant.parse("2026-01-01T00:00:00Z")));
    return fundId;
  }

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return versions.getOrDefault(fundId, List.of()).stream()
        .filter(fund -> !fund.effectiveFrom().isAfter(asOfDate))
        .max(
            (left, right) -> {
              int byDate = left.effectiveFrom().compareTo(right.effectiveFrom());
              return byDate != 0 ? byDate : Integer.compare(left.version(), right.version());
            });
  }

  @Override
  public List<Fund> activeAsOf(LocalDate asOfDate) {
    List<Fund> active = new ArrayList<>();
    for (FundId fundId : versions.keySet()) {
      resolveAsOf(fundId, asOfDate)
          .filter(fund -> fund.status() == FundStatus.ACTIVE)
          .filter(fund -> !fund.inceptionDate().isAfter(asOfDate))
          .ifPresent(active::add);
    }
    return List.copyOf(active);
  }
}
