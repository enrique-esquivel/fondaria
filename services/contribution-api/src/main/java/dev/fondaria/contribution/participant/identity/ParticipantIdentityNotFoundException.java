package dev.fondaria.contribution.participant.identity;

import java.io.Serial;

/**
 * No identity has been captured for this participant — a distinct case from {@code
 * ParticipantNotFoundException}, since the participant record itself exists; only this companion
 * row does not.
 */
public final class ParticipantIdentityNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ParticipantIdentityNotFoundException() {
    super("No identity has been captured for this participant");
  }
}
