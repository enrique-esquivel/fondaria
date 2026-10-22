package dev.fondaria.contribution.participant.web;

import dev.fondaria.contribution.participant.ParticipantLookup;

/**
 * A participant reference, resolved to the opaque id every Phase 6 endpoint is keyed by. Nothing
 * else: no name, no {@code RFC}, no employment dates (AGENTS.md §7).
 */
public record ParticipantLookupResponse(
    String participantId, String internalRef, String payrollRef) {

  public static ParticipantLookupResponse of(ParticipantLookup lookup) {
    return new ParticipantLookupResponse(
        lookup.participantId().toString(), lookup.internalRef(), lookup.payrollRef());
  }
}
