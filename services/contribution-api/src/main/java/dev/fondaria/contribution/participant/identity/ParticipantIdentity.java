package dev.fondaria.contribution.participant.identity;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One participant's encrypted identity row — {@code master.participant_identity} (Task 9.5).
 *
 * <p>Every {@code byte[]} field here is ciphertext or a wrapped key; none of it is a plaintext
 * identity value, so nothing in this record is on docs/ENGINEERING-RULES.md §7's never-log list by
 * itself. It is still never rendered — a stack trace naming this record is one debugging step away
 * from someone reaching for the decrypted value next, and {@link #toString()} exists to make that
 * step fail.
 *
 * @param wrappedDataKey the participant's own AES-256 data key, wrapped under the master key. Empty
 *     once crypto-shredded — see {@link #keyDestroyedAt()}.
 * @param encryptedDateOfBirth empty when the participant's plan resolves to a vehicle that does not
 *     require it (phase-09-identity.md Task 9.5)
 * @param dateOfBirthRequired the rule evaluation's outcome, stored so it is reproducible from this
 *     row alone rather than by re-running current fund configuration (docs/ENGINEERING-RULES.md §4)
 * @param identityCapturedAsOf the date that rule evaluation resolved the participant's plan/fund as
 *     of -- the input alongside the plan itself
 */
public record ParticipantIdentity(
    OrganizationId organizationId,
    String internalRef,
    Optional<byte[]> wrappedDataKey,
    byte[] encryptedRfc,
    byte[] encryptedCurp,
    byte[] encryptedName,
    Optional<byte[]> encryptedDateOfBirth,
    boolean dateOfBirthRequired,
    LocalDate identityCapturedAsOf,
    Instant createdAt,
    Instant updatedAt,
    Optional<Instant> keyDestroyedAt) {

  public ParticipantIdentity {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(wrappedDataKey, "wrappedDataKey must not be null");
    Objects.requireNonNull(encryptedRfc, "encryptedRfc must not be null");
    Objects.requireNonNull(encryptedCurp, "encryptedCurp must not be null");
    Objects.requireNonNull(encryptedName, "encryptedName must not be null");
    Objects.requireNonNull(encryptedDateOfBirth, "encryptedDateOfBirth must not be null");
    Objects.requireNonNull(identityCapturedAsOf, "identityCapturedAsOf must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    Objects.requireNonNull(keyDestroyedAt, "keyDestroyedAt must not be null");
    if (internalRef == null || internalRef.isBlank()) {
      throw new IllegalArgumentException("internalRef must not be blank");
    }
    if (wrappedDataKey.isPresent() == keyDestroyedAt.isPresent()) {
      throw new IllegalArgumentException(
          "exactly one of wrappedDataKey or keyDestroyedAt must be present");
    }
    if (dateOfBirthRequired && encryptedDateOfBirth.isEmpty()) {
      throw new IllegalArgumentException(
          "encryptedDateOfBirth must be present when dateOfBirthRequired is true");
    }
  }

  /** Every field but the two dates identifies a person, or is a step away from decrypting one. */
  @Override
  public String toString() {
    return "ParticipantIdentity[organizationId=" + organizationId + ", REDACTED]";
  }
}
