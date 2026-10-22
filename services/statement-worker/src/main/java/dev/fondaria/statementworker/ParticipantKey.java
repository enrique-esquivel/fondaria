package dev.fondaria.statementworker;

import dev.fondaria.common.OrganizationId;
import java.util.Objects;

/**
 * A holding's owner, without which fund: the grain every number in a monthly statement is summed
 * over (Task 7.5b). {@code ADR-0022} §4 computes the waterfall per participant, not per position —
 * a participant's opening balance, contributions and closing balance are each a sum across every
 * fund the snapshot file or {@code workflow.allotment} names for this {@link #organizationId} and
 * {@link #participantInternalRef} pair.
 *
 * <p>Not {@link dev.fondaria.common.ParticipantId}: that opaque id is what external contracts carry
 * ({@code ParticipantStatement.participantId}), resolved once per statement by {@code
 * ParticipantDirectory}. This is the internal key the snapshot file and {@code workflow.allotment}
 * are actually keyed by, and it never leaves this service (AGENTS.md §7).
 */
public record ParticipantKey(OrganizationId organizationId, String participantInternalRef) {

  public ParticipantKey {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
  }

  /** A stable, opaque string for the Redis completion set and the Kafka message key. */
  public String asKey() {
    return organizationId.value() + ":" + participantInternalRef;
  }
}
