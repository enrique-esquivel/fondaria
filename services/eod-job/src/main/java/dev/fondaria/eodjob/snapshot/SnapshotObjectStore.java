package dev.fondaria.eodjob.snapshot;

/**
 * Where a day's snapshot and its manifest are stored.
 *
 * <p>An interface rather than the S3 client itself, for the reason contribution-api's {@code
 * ObjectStore} is one: everything worth testing about the snapshot — that it ties, that a re-run
 * halts, that a dormant participant still gets a row — belongs to {@link SnapshotService} and
 * should be provable without a container in the loop. The one S3 implementation proves the wire
 * protocol separately against a real object store.
 *
 * <p><b>Write-only, and there is no delete.</b> The close writes the artefact and never reads it
 * back or removes it. Phase 8's object lock would make that structural at the bucket; until it
 * exists the guarantee is that nothing in this service can express the operation (Task 8.1, skipped
 * — see {@code TASK-LOG.md}).
 */
public interface SnapshotObjectStore {

  /**
   * Stores {@code bytes} under {@code objectKey}.
   *
   * <p>Callers must have established that overwriting is correct before calling: an S3 {@code PUT}
   * replaces. {@link SnapshotService} does that by comparing content hashes against the run already
   * recorded for the date, and never reaches here on a mismatch.
   *
   * @throws ObjectStoreUnavailableException the store could not be reached or refused the write.
   *     The pass fails and the run is recorded {@code FAILED}: a snapshot nobody can find is not
   *     one.
   */
  void put(String objectKey, byte[] bytes, String contentType);
}
