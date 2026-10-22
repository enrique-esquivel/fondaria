package dev.fondaria.valuationapi.correction;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code workflow.allotment} row struck at a NAV a correction has just superseded — Task 20.5's
 * own reason for existing: "the list of allotments affected by a correction is queryable... the
 * report that makes a NAV error recoverable at all."
 *
 * <p>Narrower than {@code allotment-job}'s own {@code AllotmentRecord} — no {@code fundId}, no
 * {@code navVersionId} — because every row this type describes shares both: the caller already
 * knows which fund and which superseded price it asked about.
 */
public record AffectedAllotment(
    OrganizationId organizationId,
    UUID batchId,
    String participantInternalRef,
    String source,
    Units units,
    boolean vested,
    TransferId transferId,
    Instant allottedAt) {

  public AffectedAllotment {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(allottedAt, "allottedAt must not be null");
  }

  /** {@code participantInternalRef} is person-identifying (docs/ENGINEERING-RULES.md §7). */
  @Override
  public String toString() {
    return "AffectedAllotment[batchId=" + batchId + ", REDACTED]";
  }
}
