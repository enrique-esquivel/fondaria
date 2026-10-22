package dev.fondaria.contribution.participant.identity;

import java.io.Serial;

/**
 * An identity-capture request is malformed or incomplete — e.g. a blank required field, or a
 * missing date of birth for a participant whose plan resolves to a retirement vehicle
 * (phase-09-identity.md Task 9.5: "Date of birth is required for retirement products"). The message
 * never echoes the submitted value, only which field is at fault (AGENTS.md §7).
 */
public final class InvalidIdentityCaptureRequestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidIdentityCaptureRequestException(String message) {
    super(message);
  }
}
