package dev.fondaria.valuationapi.objectstore;

import java.util.Optional;

/**
 * The object store a valuation file is uploaded to and read back from (ADR-0009's transport, reused
 * for valuations by Task 20.3). The file's bytes never pass through an API request: the caller is
 * handed a presigned slot, uploads directly, and this service reads the object afterwards.
 *
 * <p>An interface rather than the S3 client itself, so that the decisions worth testing — what
 * happens when an object is missing, when its checksum disagrees, when it is too large to accept —
 * are provable without a container in the loop. The one S3 implementation proves the wire protocol
 * separately against a real object store.
 *
 * <p><b>This mirrors contribution-api's port of the same name rather than sharing it.</b>
 * Extracting a common object-store module is the obvious next step and is recorded as a follow-up
 * in {@code TASK-LOG.md}; Task 20.3 names {@code services/valuation-api/} and no library, and
 * inventing a shared module unasked is the kind of scope drift AGENTS.md §10 rules out.
 */
public interface ObjectStore {

  /**
   * Issues a short-lived, presigned {@code PUT} slot for {@code objectKey}. Issuing one grants no
   * batch and creates no state: re-uploading identical bytes to a fresh slot is harmless, because a
   * batch is identified by the checksum of those bytes rather than by which URL delivered them.
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
   *     reject a provider's file for a fault on this side — and a rejected valuation file means a
   *     fund goes unpriced.
   */
  Optional<byte[]> fetch(String objectKey);
}
