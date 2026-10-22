package dev.fondaria.balanceapi.balance;

import java.io.Serial;

/**
 * No participant carries the requested id.
 *
 * <p><b>Distinct from a zero balance, and never rendered as one.</b> Zero says the participant
 * exists and holds nothing; this says the platform has no record of them. Conflating the two makes
 * a mistyped id look like a real, empty account — the same rule Task 6.4 applies to a date before a
 * participant's first contribution.
 *
 * <p>Carries no id: it reaches a problem detail, and the value came from a URL
 * (docs/ENGINEERING-RULES.md §7).
 */
public final class ParticipantNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ParticipantNotFoundException() {
    super("No such participant");
  }
}
