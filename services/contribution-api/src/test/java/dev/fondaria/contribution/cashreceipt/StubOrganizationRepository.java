package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationRevision;
import dev.fondaria.contribution.organization.OrganizationStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Just enough {@code master.organization} for the cash-receipt tests: an id is either registered or
 * it is not. Registering is done straight into the map rather than through {@code
 * OrganizationService}, so these tests never depend on registration's own behaviour.
 */
final class StubOrganizationRepository implements OrganizationRepository {

  private final Map<OrganizationId, Organization> organizations = new LinkedHashMap<>();

  OrganizationId register(TaxIdentifier taxIdentifier) {
    OrganizationId id = OrganizationId.derive(java.util.UUID.randomUUID(), taxIdentifier);
    Instant now = Instant.parse("2026-08-19T00:00:00Z");
    organizations.put(
        id,
        new Organization(
            id,
            taxIdentifier,
            "Acme S.A. de C.V.",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            1,
            now,
            now));
    return id;
  }

  @Override
  public Optional<Organization> findById(OrganizationId organizationId) {
    return Optional.ofNullable(organizations.get(organizationId));
  }

  @Override
  public boolean insertIfAbsent(Organization organization) {
    return organizations.putIfAbsent(organization.organizationId(), organization) == null;
  }

  @Override
  public void update(Organization next) {
    organizations.put(next.organizationId(), next);
  }

  @Override
  public List<Organization> findAll(int limit, int offset) {
    return List.copyOf(organizations.values());
  }

  @Override
  public List<OrganizationRevision> findRevisions(OrganizationId organizationId) {
    return List.of();
  }
}
