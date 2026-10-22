package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ageing.ValuationAgeingReport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The ageing report: business dates that have passed with no accepted valuation. */
public record ValuationAgeingResponse(
    LocalDate asOf, Instant generatedAt, List<MissingValuationResponse> missing) {

  public record MissingValuationResponse(
      UUID fundId, LocalDate valuationDate, int businessDatesLate) {}

  public static ValuationAgeingResponse of(ValuationAgeingReport report) {
    return new ValuationAgeingResponse(
        report.asOf(),
        report.generatedAt(),
        report.missing().stream()
            .map(
                missing ->
                    new MissingValuationResponse(
                        missing.fundId().value(),
                        missing.valuationDate(),
                        missing.businessDatesLate()))
            .toList());
  }
}
