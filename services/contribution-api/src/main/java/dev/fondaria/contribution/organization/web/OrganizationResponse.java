package dev.fondaria.contribution.organization.web;

import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRevision;
import java.time.Instant;

/**
 * An organization as returned by the API.
 *
 * <p><b>There is no tax-identifier field, and adding one would be a defect.</b> Only the scheme is
 * carried, which tells a client how the organization was keyed without telling it what the key is.
 * The derived {@code organizationId} is what every caller uses from here on.
 */
public record OrganizationResponse(
    String organizationId,
    String taxIdScheme,
    String legalName,
    String contractReference,
    String feeBracket,
    String status,
    int revision,
    Instant createdAt,
    Instant updatedAt) {

  public static OrganizationResponse of(Organization organization) {
    return new OrganizationResponse(
        organization.organizationId().toString(),
        organization.taxIdentifier().scheme().name(),
        organization.legalName(),
        organization.contractReference().orElse(null),
        organization.feeBracket().orElse(null),
        organization.status().name(),
        organization.revision(),
        organization.createdAt(),
        organization.updatedAt());
  }

  /** One row of the append-only history behind an organization. */
  public record Revision(
      int revision,
      String legalName,
      String contractReference,
      String feeBracket,
      String status,
      Instant recordedAt) {

    public static Revision of(OrganizationRevision revision) {
      return new Revision(
          revision.revision(),
          revision.legalName(),
          revision.contractReference().orElse(null),
          revision.feeBracket().orElse(null),
          revision.status().name(),
          revision.recordedAt());
    }
  }
}
