package dev.fondaria.contribution.fund.web;

import dev.fondaria.rules.Fund;
import java.time.Instant;

/** A fund version as returned by the API. */
public record FundResponse(
    String fundId,
    String effectiveFrom,
    int version,
    String name,
    String vehicleType,
    String currency,
    long unitLedgerId,
    long unitScale,
    String launchDate,
    long launchNavScaled,
    String status,
    Instant publishedAt) {

  public static FundResponse of(Fund fund) {
    return new FundResponse(
        fund.fundId().toString(),
        fund.effectiveFrom().toString(),
        fund.version(),
        fund.name(),
        fund.vehicleType(),
        fund.baseCurrency().name(),
        fund.unitLedgerId(),
        fund.unitScale(),
        fund.inceptionDate().toString(),
        fund.openingNav().scaled(),
        fund.status().name(),
        fund.publishedAt());
  }
}
