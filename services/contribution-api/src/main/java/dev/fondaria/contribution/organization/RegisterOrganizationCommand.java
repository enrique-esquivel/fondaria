package dev.fondaria.contribution.organization;

import dev.fondaria.common.TaxIdentifier;
import java.util.Objects;
import java.util.Optional;

/**
 * What a caller must supply to register an organization. The tax identifier arrives here once, in a
 * request body, and is the last time it travels: everything afterwards carries the derived {@code
 * OrganizationId} instead (AGENTS.md §7).
 */
public record RegisterOrganizationCommand(
    TaxIdentifier taxIdentifier,
    String legalName,
    Optional<String> contractReference,
    Optional<String> feeBracket) {

  public RegisterOrganizationCommand {
    Objects.requireNonNull(taxIdentifier, "taxIdentifier must not be null");
    Objects.requireNonNull(contractReference, "contractReference must not be null");
    Objects.requireNonNull(feeBracket, "feeBracket must not be null");
    if (legalName == null || legalName.isBlank()) {
      throw new IllegalArgumentException("legalName must not be blank");
    }
    legalName = legalName.trim();
  }
}
