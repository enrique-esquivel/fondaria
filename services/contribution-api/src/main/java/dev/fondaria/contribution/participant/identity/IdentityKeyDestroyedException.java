package dev.fondaria.contribution.participant.identity;

import java.io.Serial;

/**
 * This participant's data key was crypto-shredded (Task 9.5): the row still exists, but nothing —
 * not even the master key — can recover the data key that encrypted it, so the four ciphertext
 * fields are permanently unreadable. Distinct from {@link ParticipantIdentityNotFoundException},
 * which means no identity was ever captured at all.
 */
public final class IdentityKeyDestroyedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public IdentityKeyDestroyedException() {
    super("This participant's identity data key has been destroyed and cannot be recovered");
  }
}
