package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.FundId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A valuation that passed every check and is recorded — {@code workflow.valuation}.
 *
 * <p><b>This is what a provider reported, not a price the platform computed.</b> It is never a NAV,
 * never a balance and never summed. Task 20.4 reads one of these, reads units in issue from the
 * ledger through {@code LedgerPort}, and divides; it is that division — with the units in issue it
 * used — that becomes a NAV in {@code reference.fund_nav}.
 */
public record AcceptedValuation(
    UUID valuationId,
    UUID batchId,
    FundId fundId,
    LocalDate valuationDate,
    long reportedValueMinorUnits,
    String providerReference,
    Instant receivedAt) {

  public AcceptedValuation {
    Objects.requireNonNull(valuationId, "valuationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    if (providerReference == null || providerReference.isBlank()) {
      throw new IllegalArgumentException("providerReference must not be blank");
    }
    if (reportedValueMinorUnits < 0) {
      throw new IllegalArgumentException(
          "reportedValueMinorUnits must not be negative, got: " + reportedValueMinorUnits);
    }
    providerReference = providerReference.trim();
  }
}
