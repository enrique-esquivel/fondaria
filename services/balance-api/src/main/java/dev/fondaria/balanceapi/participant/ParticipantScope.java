package dev.fondaria.balanceapi.participant;

import dev.fondaria.common.OrganizationId;
import java.util.Objects;

/**
 * What a {@code ParticipantId} resolves to: the two components a participant's ledger holding
 * accounts are derived from.
 *
 * <p>{@code AllotmentService.participantScope} opened those accounts under {@code organizationId +
 * ":" + participantInternalRef + ":" + fundId}, and {@link #forFund} reproduces that string
 * exactly. <b>The two derivations must never drift.</b> A mismatch does not fail — it derives ids
 * for accounts that do not exist, and every participant is reported as holding nothing.
 *
 * <p>Organization first, because {@code participantInternalRef} is unique only within an
 * organization, never globally (docs/ENGINEERING-RULES.md §4, and {@code master.participant}'s own
 * primary key).
 */
public record ParticipantScope(OrganizationId organizationId, String participantInternalRef) {

  public ParticipantScope {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    if (participantInternalRef == null || participantInternalRef.isBlank()) {
      throw new IllegalArgumentException("participantInternalRef must not be blank");
    }
  }

  /** The account-derivation scope for this participant's holdings in one fund. */
  public String forFund(dev.fondaria.common.FundId fundId) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    return organizationId.scope() + ":" + participantInternalRef + ":" + fundId;
  }

  /** {@code participantInternalRef} identifies a person (docs/ENGINEERING-RULES.md §7). */
  @Override
  public String toString() {
    return "ParticipantScope[organizationId=" + organizationId + ", REDACTED]";
  }
}
