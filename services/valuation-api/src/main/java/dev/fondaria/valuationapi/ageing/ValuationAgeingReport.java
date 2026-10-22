package dev.fondaria.valuationapi.ageing;

import dev.fondaria.common.FundId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Business dates that have passed with no accepted valuation.
 *
 * <p><b>A silently absent NAV is worse than a late one.</b> Every balance in a fund is {@code units
 * × NAV} (docs/ENGINEERING-RULES.md §2), so a fund with no valuation for a business date has every
 * participant's balance quietly stale — and quietly is the problem. This is the valuation-side
 * counterpart of Task 3.4's cash reconciliation ageing report, and it exists for the same reason:
 * an absence has to be a visible state rather than a silence.
 */
public record ValuationAgeingReport(
    LocalDate asOf, Instant generatedAt, List<MissingValuation> missing) {

  public ValuationAgeingReport {
    Objects.requireNonNull(asOf, "asOf must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    missing = List.copyOf(missing);
  }

  /**
   * @param businessDatesLate how many business dates have passed since {@code valuationDate},
   *     counted in business dates rather than calendar days — a valuation missing over a long
   *     weekend is one date late, not four, and reporting it as four would make every Monday look
   *     like an incident.
   */
  public record MissingValuation(FundId fundId, LocalDate valuationDate, int businessDatesLate) {

    public MissingValuation {
      Objects.requireNonNull(fundId, "fundId must not be null");
      Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    }
  }
}
