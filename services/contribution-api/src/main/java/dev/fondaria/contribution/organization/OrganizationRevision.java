package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.Optional;

/**
 * One row of {@code master.organization_revision} — what an organization's mutable fields held at a
 * point in time. Registration writes revision 1; each accepted update appends the next.
 *
 * <p>The tax identifier is not repeated here. It cannot change (see {@link OrganizationChange}), so
 * a copy per revision would be a second place for it to leak from and could never disagree usefully
 * with the first.
 */
public record OrganizationRevision(
    OrganizationId organizationId,
    int revision,
    String legalName,
    Optional<String> contractReference,
    Optional<String> feeBracket,
    OrganizationStatus status,
    Instant recordedAt) {}
