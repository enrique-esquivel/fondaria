package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A client organization as held in {@code master.organization} — the registry ADR-0011 exists to
 * create. Without it, {@code organization_id} being a one-way derivation of the tax identifier
 * means nothing in the system can say whose money a ledger account holds.
 *
 * <p>An organization is a tenant (ADR-0005), so {@link #organizationId()} is the {@code tenant_id}
 * every other table is scoped by.
 *
 * <p>{@link TaxIdentifier} is held here because this is the one place it legitimately lives. It
 * never reaches a response, a URL, a log line or a message key — see {@link
 * dev.fondaria.contribution.organization.web.OrganizationResponse}, which has no field for it, and
 * {@code TaxIdentifier#toString()}, which redacts itself so that even an accidental interpolation
 * cannot leak it.
 *
 * @param revision which revision of this organization's mutable fields these values are; 1 at
 *     registration, incremented by each accepted update. The full history is in {@code
 *     master.organization_revision}.
 */
public record Organization(
    OrganizationId organizationId,
    TaxIdentifier taxIdentifier,
    String legalName,
    Optional<String> contractReference,
    Optional<String> feeBracket,
    OrganizationStatus status,
    int revision,
    Instant createdAt,
    Instant updatedAt) {

  public Organization {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(taxIdentifier, "taxIdentifier must not be null");
    Objects.requireNonNull(contractReference, "contractReference must not be null");
    Objects.requireNonNull(feeBracket, "feeBracket must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    legalName = requireText(legalName, "legalName");
    if (revision < 1) {
      throw new IllegalArgumentException("revision must be at least 1, got: " + revision);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
