package dev.fondaria.valuationapi.ingestion;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One delivered valuation file and where its validation stands — {@code workflow.valuation_batch}.
 *
 * @param acceptedValuationCount how many valuations this batch recorded. Empty until the batch is
 *     {@link ValuationBatchStatus#ACCEPTED}, and legitimately lower than {@code declaredRowCount}:
 *     a row redelivering an identical valuation already accepted is a no-op, not a new valuation
 *     ({@code valuation-file-v1.md} §4, check 9).
 * @param validationStartedAt the current validation attempt's lease. Cleared when the batch reaches
 *     an outcome; a stale one is what lets another instance reclaim a batch whose validator died.
 */
public record ValuationBatch(
    UUID batchId,
    String source,
    String objectKey,
    String checksumSha256,
    ValuationBatchStatus status,
    int declaredRowCount,
    OptionalInt acceptedValuationCount,
    Optional<ValuationRejection> rejection,
    Optional<Instant> validationStartedAt,
    Instant submittedAt,
    Instant statusUpdatedAt) {

  public ValuationBatch {
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(acceptedValuationCount, "acceptedValuationCount must not be null");
    Objects.requireNonNull(rejection, "rejection must not be null");
    Objects.requireNonNull(validationStartedAt, "validationStartedAt must not be null");
    Objects.requireNonNull(submittedAt, "submittedAt must not be null");
    Objects.requireNonNull(statusUpdatedAt, "statusUpdatedAt must not be null");
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    if (objectKey == null || objectKey.isBlank()) {
      throw new IllegalArgumentException("objectKey must not be blank");
    }
    if (checksumSha256 == null || checksumSha256.isBlank()) {
      throw new IllegalArgumentException("checksumSha256 must not be blank");
    }
    if (declaredRowCount <= 0) {
      throw new IllegalArgumentException("declaredRowCount must be greater than zero");
    }
    source = source.trim();
  }

  /** A newly submitted batch: received, unread, unclaimed. */
  public static ValuationBatch received(
      String source, String objectKey, String checksumSha256, int declaredRowCount, Instant now) {
    return new ValuationBatch(
        ValuationBatchId.derive(source, objectKey, checksumSha256),
        source,
        objectKey,
        checksumSha256,
        ValuationBatchStatus.RECEIVED,
        declaredRowCount,
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        now,
        now);
  }

  public ValuationBatch accepted(int valuationCount, Instant now) {
    return new ValuationBatch(
        batchId,
        source,
        objectKey,
        checksumSha256,
        ValuationBatchStatus.ACCEPTED,
        declaredRowCount,
        OptionalInt.of(valuationCount),
        Optional.empty(),
        Optional.empty(),
        submittedAt,
        now);
  }

  public ValuationBatch rejected(ValuationRejection reason, Instant now) {
    return new ValuationBatch(
        batchId,
        source,
        objectKey,
        checksumSha256,
        ValuationBatchStatus.REJECTED,
        declaredRowCount,
        OptionalInt.empty(),
        Optional.of(reason),
        Optional.empty(),
        submittedAt,
        now);
  }
}
