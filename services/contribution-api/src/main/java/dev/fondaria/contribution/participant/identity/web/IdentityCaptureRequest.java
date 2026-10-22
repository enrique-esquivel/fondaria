package dev.fondaria.contribution.participant.identity.web;

import dev.fondaria.contribution.participant.identity.IdentityCaptureCommand;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The request body {@code PUT .../identity} accepts. {@code dateOfBirth} is optional here — whether
 * it is actually required depends on the participant's plan, and {@link IdentityCaptureCommand}
 * (via {@code ParticipantIdentityService}) is what decides that, not this shape.
 */
public record IdentityCaptureRequest(String rfc, String curp, String name, LocalDate dateOfBirth) {

  IdentityCaptureCommand toCommand() {
    return new IdentityCaptureCommand(rfc, curp, name, Optional.ofNullable(dateOfBirth));
  }

  /** Every field identifies a person (AGENTS.md §7). */
  @Override
  public String toString() {
    return "IdentityCaptureRequest[REDACTED]";
  }
}
