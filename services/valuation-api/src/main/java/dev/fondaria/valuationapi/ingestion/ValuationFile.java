package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A parsed valuation file — {@code valuation-file-v1.md} §3.
 *
 * <p>Structure only. Nothing here has been checked against the submission's declarations, against
 * the fund registry, against the calendar or against what has already been accepted; those are
 * checks 4 to 9 and they belong to {@link ValuationBatchValidator}.
 */
public record ValuationFile(List<ValuationRow> rows) {

  public ValuationFile {
    Objects.requireNonNull(rows, "rows must not be null");
    rows = List.copyOf(rows);
  }

  /**
   * One fund's reported balance on one valuation date.
   *
   * @param reportedValueMinorUnits MXN cents, integer, non-negative. A pooled balance is not
   *     negative — but the NAV struck from it is free to fall, and nothing downstream clamps it
   *     (AGENTS.md §4).
   * @param providerReference the provider's own identifier for this valuation. Carried, never
   *     parsed, and never compared when deciding whether a redelivery contradicts an accepted
   *     valuation — a provider restating the same balance under a new reference is still the same
   *     valuation.
   */
  public record ValuationRow(
      FundId fundId,
      LocalDate valuationDate,
      long reportedValueMinorUnits,
      String providerReference) {

    public ValuationRow {
      Objects.requireNonNull(fundId, "fundId must not be null");
      Objects.requireNonNull(valuationDate, "valuationDate must not be null");
      Objects.requireNonNull(providerReference, "providerReference must not be null");
    }
  }
}
