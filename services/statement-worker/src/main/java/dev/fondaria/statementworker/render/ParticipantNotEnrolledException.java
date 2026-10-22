package dev.fondaria.statementworker.render;

import java.io.Serial;

/**
 * A {@code statement.jobs.v1} job names a holding the closing snapshot recorded, but {@code
 * master.participant} has no matching row. This is a genuine data-integrity gap — the snapshot and
 * {@code master.participant} have diverged — not a transient failure, so it is treated as poison:
 * dead-lettered and the consumer halted (docs/ENGINEERING-RULES.md §4: never skip a message you
 * can't process).
 */
public final class ParticipantNotEnrolledException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ParticipantNotEnrolledException(String message) {
    super(message);
  }
}
