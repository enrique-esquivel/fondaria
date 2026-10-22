package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import java.time.LocalDate;

/**
 * Supplies the raw total balance an external provider reported for a fund on a valuation date — the
 * number {@link ReportedBalanceValuationSource} strikes a NAV per unit from.
 *
 * <p>This is the seam between striking (this module) and ingestion (Task 20.3): the real
 * implementation reads a validated, checksum-verified valuation, staged the same way Task 3.4
 * stages a contribution file before its rows are trusted. It does not exist yet, so this interface
 * is what lets the Model B adapter be built and tested now, ahead of that pipeline.
 */
public interface ReportedBalanceSource {

  Money reportedValueFor(FundId fund, LocalDate date);
}
