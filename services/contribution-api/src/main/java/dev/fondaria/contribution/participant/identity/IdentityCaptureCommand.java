package dev.fondaria.contribution.participant.identity;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The four fields Task 9.5 names: RFC, CURP, name and date of birth. RFC, CURP and name are always
 * required; date of birth is required only when the participant's plan resolves to a retirement
 * vehicle — {@link ParticipantIdentityService} is what knows that, so validation here is split
 * between what this record can check alone ({@link #requireFieldsPresent()}) and what needs that
 * plan lookup ({@link #requireDateOfBirthIfNeeded}).
 */
public record IdentityCaptureCommand(
    String rfc, String curp, String name, Optional<LocalDate> dateOfBirth) {

  /**
   * Generous relative to any real RFC/CURP/name, but a system boundary still bounds it (AGENTS.md
   * §4a: only validate at the boundary) -- otherwise the request body has no ceiling on the
   * ciphertext this ends up encrypting and storing.
   */
  private static final int MAX_FIELD_LENGTH = 256;

  public IdentityCaptureCommand {
    Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null");
  }

  void requireFieldsPresent() {
    requireText(rfc, "rfc");
    requireText(curp, "curp");
    requireText(name, "name");
  }

  void requireDateOfBirthIfNeeded(boolean required) {
    if (required && dateOfBirth.isEmpty()) {
      throw new InvalidIdentityCaptureRequestException(
          "dateOfBirth is required: this participant's plan resolves to a retirement vehicle");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvalidIdentityCaptureRequestException(field + " must not be blank");
    }
    if (value.length() > MAX_FIELD_LENGTH) {
      throw new InvalidIdentityCaptureRequestException(
          field + " must not exceed " + MAX_FIELD_LENGTH + " characters");
    }
  }

  /** Every field identifies a person (AGENTS.md §7). */
  @Override
  public String toString() {
    return "IdentityCaptureCommand[REDACTED]";
  }
}
