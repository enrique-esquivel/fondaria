package dev.fondaria.contribution.objectstore;

import java.util.Optional;

/**
 * The object store a dispersion file is uploaded to and read back from (ADR-0009). The file's bytes
 * never pass through an API request: the caller is handed a presigned slot, uploads directly, and
 * this service reads the object afterwards.
 *
 * <p>An interface rather than the S3 client itself, for the reason {@code CashReceiptRepository} is
 * one: the decisions worth testing — what happens when an object is missing, when its checksum
 * disagrees, when it is too large to accept — belong to the validator, and should be provable
 * without a container in the loop. The one S3 implementation proves the wire protocol separately
 * against a real object store.
 */
public interface ObjectStore {

  /**
   * Issues a short-lived, presigned {@code PUT} slot for {@code objectKey}. Issuing one grants no
   * batch and creates no state: re-uploading identical bytes to a fresh slot is harmless, because a
   * batch is identified by the checksum of those bytes rather than by which URL delivered them
   * (ADR-0009).
   */
  PresignedUpload presignUpload(String objectKey);

  /**
   * Reads an object in full, or reports it absent.
   *
   * @return empty when no object exists under {@code objectKey} — a submission naming an object
   *     nobody uploaded, which is a rejection rather than a failure
   * @throws ObjectTooLargeException the object exceeds the configured maximum accepted size, so it
   *     is never read into memory
   * @throws ObjectStoreUnavailableException the store could not be reached or refused the read.
   *     Distinct from absence on purpose: an outage must leave the batch to be retried, never
   *     reject an employer's file for a fault on this side.
   */
  Optional<byte[]> fetch(String objectKey);
}
