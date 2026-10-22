package dev.fondaria.contribution.participant;

import dev.fondaria.common.ParticipantId;
import java.util.Objects;

/**
 * The three fields {@code GET .../participants} may return (AGENTS.md §7: no name, no {@code RFC}).
 * A separate shape from {@link Participant} because that record carries the employment fields the
 * lookup must never expose, and because {@link ParticipantId} — assigned by {@code
 * master.participant.participant_id}'s column default (Task 6.2) — is not one of {@link
 * Participant}'s own components.
 */
public record ParticipantLookup(
    ParticipantId participantId, String internalRef, String payrollRef) {

  public ParticipantLookup {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(internalRef, "internalRef must not be null");
    Objects.requireNonNull(payrollRef, "payrollRef must not be null");
  }

  /** Every field identifies a person (AGENTS.md §7). */
  @Override
  public String toString() {
    return "ParticipantLookup[REDACTED]";
  }
}
