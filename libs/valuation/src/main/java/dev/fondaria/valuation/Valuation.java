package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The result of {@link ValuationSource#valuationFor}: the raw balance the external provider
 * reported for {@code fundId} on {@code valuationDate}, and the price per unit struck from it.
 *
 * <p>This is the input Task 20.4's strike persists — {@code source} names which model produced it
 * ({@code "MODEL_B"} today), the same value the {@code fund_nav} row's {@code source} column will
 * carry.
 */
public record Valuation(
    FundId fundId,
    LocalDate valuationDate,
    Money reportedValue,
    NavPerUnit navPerUnit,
    String source) {

  public Valuation {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    Objects.requireNonNull(reportedValue, "reportedValue must not be null");
    Objects.requireNonNull(navPerUnit, "navPerUnit must not be null");
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    source = source.trim();
  }
}
