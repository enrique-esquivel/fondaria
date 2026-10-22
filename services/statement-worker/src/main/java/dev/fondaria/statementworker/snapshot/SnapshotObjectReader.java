package dev.fondaria.statementworker.snapshot;

import java.io.InputStream;

/** Opens one of Task 7.4's dense-snapshot objects for streaming. */
public interface SnapshotObjectReader {

  /**
   * The gzipped NDJSON positions file's raw bytes, as a stream — decompression is the caller's
   * ({@link PositionFileReader}'s) job, matching {@code PositionFileWriter} keeping the digest and
   * the compressor as separate stages.
   *
   * @throws SnapshotObjectUnavailableException if the store did not serve the object
   */
  InputStream open(String objectKey);
}
