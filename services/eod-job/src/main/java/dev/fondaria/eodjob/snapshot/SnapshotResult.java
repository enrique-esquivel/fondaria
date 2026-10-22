package dev.fondaria.eodjob.snapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one snapshot pass produced.
 *
 * @param manifest present only when this pass stored a file. A re-run that reproduced an existing
 *     snapshot byte for byte deliberately does not render or store a manifest — the snapshot of
 *     record for that date, and the chain link the next date will follow, are the ones agreed the
 *     first time. Re-rendering would risk a manifest whose {@code previousManifestSha256} had moved
 *     underneath it while the positions had not.
 * @param newlyStored whether this pass wrote to object storage. False means the file was already
 *     there and identical.
 */
public record SnapshotResult(
    SnapshotRun run,
    List<SnapshotFundTotal> fundTotals,
    Optional<SnapshotManifest> manifest,
    boolean newlyStored) {

  public SnapshotResult {
    Objects.requireNonNull(run, "run must not be null");
    Objects.requireNonNull(manifest, "manifest must not be null");
    fundTotals = List.copyOf(fundTotals);
  }

  /** Rows in the file — the control total Task 7.5 counts statements against. */
  public long positionCount() {
    return run.positionCount();
  }

  /** Every fund's file total agreed with its units in issue. Always true on a settled run. */
  public boolean ties() {
    return fundTotals.stream().allMatch(SnapshotFundTotal::ties);
  }
}
