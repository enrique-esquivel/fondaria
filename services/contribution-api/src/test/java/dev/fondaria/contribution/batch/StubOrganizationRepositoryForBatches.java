package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationRevision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Just enough {@code master.organization} for the batch tests: an id is either registered or it is
 * not, which is the only thing the batch path asks of it.
 */
final class StubOrganizationRepositoryForBatches implements OrganizationRepository {

  private final Map<OrganizationId, Organization> organizations = new LinkedHashMap<>();

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
