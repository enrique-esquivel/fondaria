package dev.fondaria.contribution.organization;

import java.util.Objects;
import java.util.Optional;

/**
 * The mutable fields of an organization, as supplied by an update. Every field is optional and an
 * absent one means "leave as it is", so a caller correcting a legal name cannot silently blank a
 * fee bracket it never sent.
 *
 * <p>There is deliberately no field for the tax identifier. It is what {@code organization_id} is
 * derived from, so changing it would change the id — and the id is what every ledger account, every
 * historical transfer and every downstream row already points at. An employer whose {@code RFC}
 * genuinely changes is a new organization, registered as one.
 */
public record OrganizationChange(
    Optional<String> legalName,
    Optional<String> contractReference,
    Optional<String> feeBracket,
    Optional<OrganizationStatus> status) {

  public OrganizationChange {
    Objects.requireNonNull(legalName, "legalName must not be null");
    Objects.requireNonNull(contractReference, "contractReference must not be null");
    Objects.requireNonNull(feeBracket, "feeBracket must not be null");
    Objects.requireNonNull(status, "status must not be null");
    legalName = legalName.map(OrganizationChange::requireText);
  }

  /** Whether this change would alter anything at all. */
  public boolean isEmpty() {
    return legalName.isEmpty()
        && contractReference.isEmpty()
        && feeBracket.isEmpty()
        && status.isEmpty();
  }

  /** Applies this change to {@code current}, returning the values the next revision should hold. */
  public Organization applyTo(Organization current, java.time.Instant now) {
    Objects.requireNonNull(current, "current must not be null");
    Objects.requireNonNull(now, "now must not be null");
    return new Organization(
        current.organizationId(),
        current.taxIdentifier(),
        legalName.orElse(current.legalName()),
        contractReference.or(current::contractReference),
        feeBracket.or(current::feeBracket),
        status.orElse(current.status()),
        current.revision() + 1,
        current.createdAt(),
        now);
  }

  private static String requireText(String value) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("legalName must not be blank");
    }
    return value.trim();
  }
}
