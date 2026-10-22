package dev.fondaria.eodjob.snapshot;

import dev.fondaria.eodjob.config.SnapshotProperties;

/**
 * The snapshot file grew past the configured ceiling.
 *
 * <p>A bound rather than an unbounded buffer, for the reason {@link PositionFileWriter} records:
 * the file is built in memory, and a close that dies of an {@code OutOfMemoryError} tells an
 * operator nothing about which step was running or how far past the expected size it got. This
 * carries both numbers.
 *
 * <p>It is a configuration failure, not a data one — the positions are real and the platform has
 * simply outgrown a limit set for a smaller book. The remedy is to raise {@code
 * SNAPSHOT_MAX_OBJECT_BYTES}, which is why the message says so.
 */
public class SnapshotTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SnapshotTooLargeException(long bytesWritten, long maximumObjectBytes) {
    super(
        "the snapshot file reached "
            + bytesWritten
            + " compressed bytes, past the configured maximum of "
            + maximumObjectBytes
            + "; raise SNAPSHOT_MAX_OBJECT_BYTES, up to "
            + SnapshotProperties.MAX_OBJECT_BYTES);
  }
}
