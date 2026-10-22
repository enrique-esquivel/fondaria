package dev.fondaria.eodjob.snapshot;

/**
 * The lifecycle of one dense-snapshot pass ({@code workflow.snapshot_run}, {@code V28}).
 *
 * <p>{@code RUNNING -> COMPLETED | FAILED}, the same shape {@code RunStatus} models for internal
 * reconciliation. A pass that dies stays {@code RUNNING}, which is a distinct and honest state: an
 * operator can tell "the process was killed" from "the snapshot did not tie", and nothing
 * downstream treats either as a snapshot.
 */
public enum SnapshotStatus {
  RUNNING,
  COMPLETED,
  FAILED;

  /** Parses a stored value, refusing anything the schema's own CHECK would not have allowed. */
  public static SnapshotStatus parse(String value) {
    for (SnapshotStatus status : values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("unknown snapshot status: " + value);
  }
}
