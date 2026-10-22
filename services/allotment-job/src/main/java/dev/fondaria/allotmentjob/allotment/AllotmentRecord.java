package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.allotmentjob.batch.ContributionSource;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code workflow.allotment} (V16) — the audit trail AGENTS.md §4 requires: "every unit
 * allotment records which NAV version it used." One record corresponds to exactly one unit-ledger
 * transfer in the {@code CONTRIBUTION_ALLOTMENT} chain (chart-of-accounts.md §4).
 *
 * @param source which of a participant's contribution sources this record is for — {@code EMPLOYEE}
 *     and {@code VOLUNTARY} always land vested, {@code EMPLOYER} depends on {@link #vested}
 * @param navVersionId the {@code reference.fund_nav} row this allotment struck units at
 * @param units units issued, floored to the unit scale — never the un-floored quotient
 * @param vested which account this allotment landed in: {@code PARTICIPANT_HOLDINGS_VESTED} when
 *     {@code true}, {@code PARTICIPANT_HOLDINGS_UNVESTED} when {@code false}
 * @param transferId the deterministically-derived unit-ledger transfer this record names
 */
public record AllotmentRecord(
    OrganizationId organizationId,
    UUID batchId,
    String participantInternalRef,
    ContributionSource source,
    FundId fundId,
    long navVersionId,
    Units units,
    boolean vested,
    TransferId transferId,
    Instant allottedAt) {

  public AllotmentRecord {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(allottedAt, "allottedAt must not be null");
    if (units.scaled() <= 0) {
      throw new IllegalArgumentException("units must be positive");
    }
  }

  /** Every field but the amounts is person-identifying: a participant reference. */
  @Override
  public String toString() {
    return "AllotmentRecord[batchId=" + batchId + ", REDACTED]";
  }
}
