package dev.fondaria.contribution.participant;

import java.io.Serial;

/**
 * No participant resolves under the reference a caller supplied. The message never echoes the
 * reference itself — it identifies a person exactly as an {@code RFC} does
 * (docs/ENGINEERING-RULES.md §7).
 */
public final class ParticipantNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ParticipantNotFoundException() {
    super("No participant is registered under the reference supplied");
  }
}
