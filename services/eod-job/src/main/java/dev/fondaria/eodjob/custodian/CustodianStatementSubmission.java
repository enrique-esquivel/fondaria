package dev.fondaria.eodjob.custodian;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One provider statement as submitted for ingestion, before it has an id or a received time.
 *
 * <p>The task names no file format, so this carries already-parsed figures rather than raw bytes —
 * whatever reads the provider's actual file or feed is upstream of this boundary and out of scope
 * here, the same way {@code AllotmentService} takes rows rather than a CSV.
 *
 * @param reportedUnitsScaled units at 1e-8 the provider reports settled and held
 * @param reportedCashCents MXN cents the provider reports settled and held for the fund
 * @param pendingTradeUnitsScaled signed units the provider reports traded but not yet settled —
 *     zero if the statement carries none, in which case every drift is a real break
 * @param pendingTradeCashCents the cash-side counterpart of {@code pendingTradeUnitsScaled}
 */
public record CustodianStatementSubmission(
    UUID providerAccountId,
    LocalDate businessDate,
    long reportedUnitsScaled,
    long reportedCashCents,
    long pendingTradeUnitsScaled,
    long pendingTradeCashCents,
    String source,
    String checksumSha256) {

  public CustodianStatementSubmission {
    Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    if (reportedUnitsScaled < 0) {
      throw new IllegalArgumentException("reportedUnitsScaled must not be negative");
    }
    if (reportedCashCents < 0) {
      throw new IllegalArgumentException("reportedCashCents must not be negative");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    if (checksumSha256 == null || checksumSha256.isBlank()) {
      throw new IllegalArgumentException("checksumSha256 must not be blank");
    }
  }
}
