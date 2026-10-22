package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.valuation.ReportedBalanceSource;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * The implementation {@link ReportedBalanceSource}'s javadoc anticipated: "the real implementation
 * reads a validated, checksum-verified valuation, staged the same way Task 3.4 stages a
 * contribution file before its rows are trusted."
 *
 * <p>This is the seam between ingestion (this service, Task 20.3) and striking (Task 20.4, through
 * {@code ReportedBalanceValuationSource}). It hands back <b>only what a provider reported and this
 * service accepted</b> — never a NAV, never an interpolation, never a fallback to a neighbouring
 * date. A fund with no accepted valuation for the date asked for has no reported balance, and
 * saying so is the correct answer; inventing one would put a price the platform made up in front of
 * every participant in that fund.
 */
@Component
public class StoredReportedBalanceSource implements ReportedBalanceSource {

  private final AcceptedValuationRepository valuations;

  public StoredReportedBalanceSource(AcceptedValuationRepository valuations) {
    this.valuations = valuations;
  }

  /**
   * @throws NoSuchElementException no valuation has been accepted for this fund on this date. The
   *     ageing report ({@code ValuationAgeingService}) is what makes that absence visible before a
   *     strike ever asks.
   */
  @Override
  public Money reportedValueFor(FundId fund, LocalDate date) {
    AcceptedValuation valuation =
        valuations
            .find(fund, date)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "No valuation has been accepted for fund " + fund + " on " + date));
    // MXN by construction: v1 is MXN-only (AGENTS.md §1) and the file carries no currency
    // column for that reason. A fund's base currency lives in reference.fund_identity and
    // is what Task 20.4 denominates the struck NAV in.
    return Money.of(valuation.reportedValueMinorUnits(), CurrencyCode.MXN);
  }
}
