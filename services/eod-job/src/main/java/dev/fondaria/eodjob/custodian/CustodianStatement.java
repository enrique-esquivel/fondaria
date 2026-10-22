package dev.fondaria.eodjob.custodian;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A {@link CustodianStatementSubmission} as recorded — the row the reconciliation pass reads. */
public record CustodianStatement(
    UUID statementId,
    UUID providerAccountId,
    LocalDate businessDate,
    long reportedUnitsScaled,
    long reportedCashCents,
    long pendingTradeUnitsScaled,
    long pendingTradeCashCents,
    String source,
    String checksumSha256,
    Instant receivedAt) {

  public CustodianStatement {
    Objects.requireNonNull(statementId, "statementId must not be null");
    Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");
  }
}
