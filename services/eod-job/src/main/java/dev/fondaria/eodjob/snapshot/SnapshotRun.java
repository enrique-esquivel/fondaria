package dev.fondaria.eodjob.snapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code workflow.snapshot_run} ({@code V28}) — the record that a dense snapshot was
 * taken for a business date, and the hashes a re-run is measured against.
 *
 * @param positionsSha256 SHA-256 of the <b>uncompressed</b> NDJSON, lowercase hex — not of the
 *     stored {@code .gz} object; see {@link PositionFile#contentSha256()} for why. To reproduce it
 *     from the archive, decompress first. Present on a {@code COMPLETED} run and on a {@code
 *     RUNNING} one that has reached the point of storing; absent otherwise.
 * @param previousManifestSha256 the manifest hash of the most recent completed snapshot for a
 *     strictly earlier business date. Absent only on the first snapshot ever taken.
 * @param failureReason operator-facing words on a {@code FAILED} run. Never an exception message,
 *     never a hostname (AGENTS.md §7).
 */
public record SnapshotRun(
    UUID runId,
    LocalDate businessDate,
    SnapshotStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    Optional<Long> durationMillis,
    long positionCount,
    int fundCount,
    Optional<String> positionsObjectKey,
    Optional<String> positionsSha256,
    Optional<String> manifestObjectKey,
    Optional<String> manifestSha256,
    Optional<String> previousManifestSha256,
    Optional<String> failureReason) {

  public SnapshotRun {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(durationMillis, "durationMillis must not be null");
    Objects.requireNonNull(positionsObjectKey, "positionsObjectKey must not be null");
    Objects.requireNonNull(positionsSha256, "positionsSha256 must not be null");
    Objects.requireNonNull(manifestObjectKey, "manifestObjectKey must not be null");
    Objects.requireNonNull(manifestSha256, "manifestSha256 must not be null");
    Objects.requireNonNull(previousManifestSha256, "previousManifestSha256 must not be null");
    Objects.requireNonNull(failureReason, "failureReason must not be null");
  }

  /** Whether this run is a snapshot the rest of the close may build on. */
  public boolean isComplete() {
    return status == SnapshotStatus.COMPLETED;
  }
}
