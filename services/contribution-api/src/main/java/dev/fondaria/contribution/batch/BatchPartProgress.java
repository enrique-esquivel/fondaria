package dev.fondaria.contribution.batch;

import java.util.Objects;
import java.util.UUID;

/**
 * Where one batch sits within its file's parts, and how much of that file has been priced
 * (ADR-0017).
 *
 * <p>A file larger than one linked chain is allotted part by part, so "has my file been allotted"
 * stops being a single status and becomes a count. This is that count — the only place in the
 * platform that aggregates over batches, and deliberately not over money: it counts statuses, never
 * cents. What an employer is owed in units is read from the ledger, as always.
 *
 * @param fileBatchId the submitted batch — this batch itself when it is a file, its parent when it
 *     is a part. The id the employer actually submitted, and the one their wire funded.
 * @param partNumber this batch's own 1-based position, {@code 1} for a file
 * @param partCount how many batches the file was divided into, {@code 1} when it was not divided
 * @param allottedParts how many of them have reached {@code ALLOTTED}. Between {@code 0} and {@code
 *     partCount}; anything short of {@code partCount} is a file still being priced, which is a
 *     state, not a fault.
 */
public record BatchPartProgress(
    UUID fileBatchId, int partNumber, int partCount, int allottedParts) {

  public BatchPartProgress {
    Objects.requireNonNull(fileBatchId, "fileBatchId must not be null");
    if (partNumber < 1 || partCount < 1 || partNumber > partCount) {
      throw new IllegalArgumentException(
          "partNumber must be within 1.." + partCount + ", was " + partNumber);
    }
    if (allottedParts < 0 || allottedParts > partCount) {
      throw new IllegalArgumentException(
          "allottedParts must be within 0.." + partCount + ", was " + allottedParts);
    }
  }

  /** Whether this batch's file was divided at all. A file that was not needs no explaining. */
  public boolean isSplit() {
    return partCount > 1;
  }
}
