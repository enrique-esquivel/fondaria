package dev.fondaria.contribution.participant.identity;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The plaintext identity fields, held only for as long as a caller inside this service needs them.
 * Deliberately never returned by any controller — {@link ParticipantIdentityService#readIdentity}
 * exists for the acceptance-criteria proof that decryption works and for a future rule (e.g. a
 * permanence check keyed on age) to consume, not for an HTTP response to carry a government id
 * number in.
 */
public record DecryptedIdentity(
    String rfc, String curp, String name, Optional<LocalDate> dateOfBirth) {

  public DecryptedIdentity {
    Objects.requireNonNull(rfc, "rfc must not be null");
    Objects.requireNonNull(curp, "curp must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null");
  }

  @Override
  public String toString() {
    return "DecryptedIdentity[REDACTED]";
  }
}
