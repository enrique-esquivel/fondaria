package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.util.Objects;

/**
 * One participant's holding in one fund: the triple a pair of ledger holding account ids is derived
 * from.
 *
 * <p>{@link #scope()} <b>must reproduce {@code AllotmentService.participantScope} exactly</b> —
 * {@code organizationId : participantInternalRef : fundId} — which is the same requirement
 * balance-api's {@code ParticipantScope.forFund} carries, and it is duplicated here for the same
 * reason it is duplicated there: services do not depend on each other's source trees. Drift does
 * not throw. It derives ids for accounts that do not exist, reads every participant as holding
 * nothing, and reports the whole platform as drifted — a break on every fund with no indication
 * that the cause is a string.
 *
 * <p>Organization first, because {@code participantInternalRef} is unique only within an
 * organization, never globally (AGENTS.md §4).
 */
public record HoldingKey(
    OrganizationId organizationId, String participantInternalRef, FundId fundId) {

  public HoldingKey {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    if (participantInternalRef == null || participantInternalRef.isBlank()) {
      throw new IllegalArgumentException("participantInternalRef must not be blank");
    }
  }

  /** The account-derivation scope for this participant's holdings in this fund. */
  public String scope() {
    return organizationId.scope() + ":" + participantInternalRef + ":" + fundId;
  }

  /** {@code participantInternalRef} identifies a person (AGENTS.md §7). */
  @Override
  public String toString() {
    return "HoldingKey[organizationId=" + organizationId + ", fundId=" + fundId + ", REDACTED]";
  }
}
