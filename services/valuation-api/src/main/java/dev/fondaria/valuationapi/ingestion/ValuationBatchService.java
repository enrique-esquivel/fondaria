package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.valuationapi.objectstore.ObjectStore;
import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The two calls of the ingestion transport: hand out an upload slot, then accept a batch by
 * reference (ADR-0009, reused by Task 20.3).
 *
 * <h2>Nothing here reads the file</h2>
 *
 * <p>Submission commits a {@code RECEIVED} row and returns {@code 202}. {@link
 * ValuationIngestionRunner} picks the batch up afterwards. A valuation file is small, so this is
 * less about request duration than it is about keeping one ingestion mechanism rather than two: the
 * contribution path already works this way, and a second shape would be a second set of failure
 * modes to reason about.
 *
 * <h2>Why there is no idempotency key</h2>
 *
 * <p>A batch <i>is</i> {@code (source, objectKey, checksumSha256)}, and its id is derived from
 * those three values, so redelivering a file converges on the same row and the database refuses the
 * duplicate. "Same key, different body" cannot be expressed: a different body is a different
 * checksum, and a different checksum is a different batch.
 */
@Service
public class ValuationBatchService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ValuationBatchService.class);

  private static final int MAXIMUM_FILE_NAME = 80;

  private final ValuationBatchRepository batches;
  private final ObjectStore objectStore;
  private final Clock clock;

  public ValuationBatchService(
      ValuationBatchRepository batches, ObjectStore objectStore, Clock clock) {
    this.batches = batches;
    this.objectStore = objectStore;
    this.clock = clock;
  }

  /**
   * Issues a presigned upload slot under a key this service chooses.
   *
   * <p>The caller's file name is carried through for audit and never used as the key: it is
   * caller-controlled text, and a key built from it could name any object in the bucket. A random
   * UUID makes the key unguessable, and the source prefix is what {@link #submit} later checks a
   * submitted key against.
   */
  public PresignedUpload createUploadSlot(String source, String fileName) {
    String key = prefix(source) + UUID.randomUUID() + "-" + sanitize(fileName);
    LOGGER.info("Issued a valuation-file upload slot, source={}", source);
    return objectStore.presignUpload(key);
  }

  /**
   * Accepts a batch by reference, or returns the batch this file already is.
   *
   * @throws InvalidValuationSubmissionException the object key does not sit under this source's
   *     prefix
   */
  public ValuationBatch submit(
      String source, String objectKey, String checksumSha256, int declaredRowCount) {
    requireOwnKey(source, objectKey);

    ValuationBatch submitted =
        batches.submit(
            ValuationBatch.received(
                source, objectKey, checksumSha256, declaredRowCount, clock.instant()));
    LOGGER.info(
        "Valuation batch submitted, batchId={}, source={}, status={}",
        submitted.batchId(),
        source,
        submitted.status());
    return submitted;
  }

  public ValuationBatch get(UUID batchId) {
    return batches.find(batchId).orElseThrow(() -> new ValuationBatchNotFoundException(batchId));
  }

  /**
   * A submitted key must sit under its own source's prefix.
   *
   * <p>Cheap, and it closes the same hole the contribution path closes with an organization prefix:
   * without it one provider could submit another's object key and learn from the resulting status
   * whether that file exists and parses.
   */
  private void requireOwnKey(String source, String objectKey) {
    if (!objectKey.startsWith(prefix(source))) {
      throw new InvalidValuationSubmissionException(
          "objectKey does not belong to this source; use a key from the upload-url call for the"
              + " same source");
    }
  }

  /** {@code valuations/{source}/} — see {@code valuation-file-v1.md} §1.2. */
  private static String prefix(String source) {
    return "valuations/" + sanitize(source) + "/";
  }

  /**
   * Reduced to characters that cannot change what an object key means. Anything else — a slash, a
   * {@code ..}, a control character, a percent escape — is replaced rather than rejected: the name
   * is decoration on a key this service already made unique.
   */
  private static String sanitize(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "valuation-file";
    }
    StringBuilder sanitized = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length() && sanitized.length() < MAXIMUM_FILE_NAME; i++) {
      char c = trimmed.charAt(i);
      boolean safe =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '-'
              || c == '_';
      // A second consecutive dot becomes a dash, so the result can never contain "..".
      boolean dotAfterDot =
          c == '.' && !sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) == '.';
      sanitized.append(safe && !dotAfterDot ? c : '-');
    }
    return sanitized.toString();
  }
}
