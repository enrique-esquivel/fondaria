package dev.fondaria.valuationapi.support;

import dev.fondaria.common.FundId;
import dev.fondaria.valuationapi.ingestion.AcceptedValuation;
import dev.fondaria.valuationapi.ingestion.AcceptedValuationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An {@link AcceptedValuationRepository} backed by a map, keyed the way the real table is uniquely
 * constrained: {@code (fundId, valuationDate)}.
 *
 * <p>Keying it that way is the point — it makes "a valuation for a date already live is not an
 * overwrite" hold here too, so a test that expects a second insert to be refused is testing the
 * rule rather than the absence of a constraint in a fake.
 */
public final class InMemoryAcceptedValuationRepository implements AcceptedValuationRepository {

  private final Map<String, AcceptedValuation> byFundAndDate = new LinkedHashMap<>();

  @Override
  public Optional<AcceptedValuation> find(FundId fundId, LocalDate valuationDate) {
    return Optional.ofNullable(byFundAndDate.get(key(fundId, valuationDate)));
  }

  @Override
  public int insertAll(Collection<AcceptedValuation> valuations) {
    for (AcceptedValuation valuation : valuations) {
      String key = key(valuation.fundId(), valuation.valuationDate());
      if (byFundAndDate.putIfAbsent(key, valuation) != null) {
        throw new IllegalStateException("A valuation is already live for " + key);
      }
    }
    return valuations.size();
  }

  @Override
  public List<LocalDate> valuationDatesBetween(
      FundId fundId, LocalDate fromInclusive, LocalDate toInclusive) {
    List<LocalDate> dates = new ArrayList<>();
    for (AcceptedValuation valuation : byFundAndDate.values()) {
      if (valuation.fundId().equals(fundId)
          && !valuation.valuationDate().isBefore(fromInclusive)
          && !valuation.valuationDate().isAfter(toInclusive)) {
        dates.add(valuation.valuationDate());
      }
    }
    dates.sort(LocalDate::compareTo);
    return List.copyOf(dates);
  }

  /** Seeds an already-accepted valuation, as a previous delivery would have left it. */
  public void seed(FundId fundId, LocalDate valuationDate, long reportedValueMinorUnits) {
    byFundAndDate.put(
        key(fundId, valuationDate),
        new AcceptedValuation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            fundId,
            valuationDate,
            reportedValueMinorUnits,
            "SEEDED",
            Instant.parse("2026-01-01T00:00:00Z")));
  }

  public int size() {
    return byFundAndDate.size();
  }

  private static String key(FundId fundId, LocalDate valuationDate) {
    return fundId.value() + "@" + valuationDate;
  }
}
