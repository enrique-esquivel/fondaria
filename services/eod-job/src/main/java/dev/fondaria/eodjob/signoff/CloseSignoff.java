package dev.fondaria.eodjob.signoff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code workflow.close_signoff} ({@code V29}) — the fact that a business date's daily
 * close has been proved three ways and may be reported from.
 *
 * <p>There is no status here, unlike the three run tables this depends on. A row exists iff the
 * date is signed off; {@link #announcedAt} is the only thing that changes after the row is written,
 * and only once — from empty to set, when {@code close.signed-off.v1} is confirmed.
 *
 * @param signedOffAt when the row was written — {@code StorableClock}, microsecond precision
 * @param snapshotPositionCount {@code workflow.snapshot_run.position_count} for the snapshot of
 *     record: the control total Task 7.5b's statement fan-out must reproduce exactly
 * @param announcedAt when {@code close.signed-off.v1} was confirmed by Redpanda, or empty while the
 *     row is written but the event is not yet out (a crash between the two, or a broker outage)
 */
public record CloseSignoff(
    LocalDate businessDate,
    Instant signedOffAt,
    UUID reconciliationRunId,
    UUID custodianRunId,
    UUID snapshotRunId,
    long snapshotPositionCount,
    String snapshotObjectKey,
    String snapshotManifestKey,
    Optional<Instant> announcedAt) {

  public CloseSignoff {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(signedOffAt, "signedOffAt must not be null");
    Objects.requireNonNull(reconciliationRunId, "reconciliationRunId must not be null");
    Objects.requireNonNull(custodianRunId, "custodianRunId must not be null");
    Objects.requireNonNull(snapshotRunId, "snapshotRunId must not be null");
    Objects.requireNonNull(snapshotObjectKey, "snapshotObjectKey must not be null");
    Objects.requireNonNull(snapshotManifestKey, "snapshotManifestKey must not be null");
    Objects.requireNonNull(announcedAt, "announcedAt must not be null");
    if (snapshotPositionCount < 0) {
      throw new IllegalArgumentException("snapshotPositionCount must not be negative");
    }
  }

  /** Whether {@code close.signed-off.v1} has been confirmed for this date. */
  public boolean isAnnounced() {
    return announcedAt.isPresent();
  }
}
