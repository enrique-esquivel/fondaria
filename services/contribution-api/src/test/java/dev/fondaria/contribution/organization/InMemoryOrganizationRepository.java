package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link OrganizationRepository}, so the decisions {@link OrganizationService} makes —
 * one organization per tax identifier, an update that appends rather than overwrites, a ledger left
 * alone by an update — are provable without a database in the loop. The SQL is proved separately,
 * against a real PostgreSQL, in {@code JdbcOrganizationRepositoryIntegrationTest}.
 *
 * <p>It mirrors the real repository's contract rather than simplifying it: {@code insertIfAbsent}
 * really is conditional, and {@code update} really does append a revision row.
 */
final class InMemoryOrganizationRepository implements OrganizationRepository {

  private final Map<OrganizationId, Organization> current = new LinkedHashMap<>();
  private final Map<OrganizationId, List<OrganizationRevision>> history = new LinkedHashMap<>();

  @Override
  public boolean insertIfAbsent(Organization organization) {
    if (current.containsKey(organization.organizationId())) {
      return false;
    }
    current.put(organization.organizationId(), organization);
    appendRevision(organization);
    return true;
  }

  @Override
  public void update(Organization next) {
    Organization existing = current.get(next.organizationId());
    if (existing == null) {
      throw new OrganizationNotFoundException(next.organizationId());
    }
    if (next.revision() != existing.revision() + 1) {
      throw new IllegalStateException("Organization revision moved under this update");
    }
    current.put(next.organizationId(), next);
    appendRevision(next);
  }

  @Override
  public Optional<Organization> findById(OrganizationId organizationId) {
    return Optional.ofNullable(current.get(organizationId));
  }

  @Override
  public List<Organization> findAll(int limit, int offset) {
    return current.values().stream()
        .sorted(Comparator.comparing(Organization::createdAt))
        .skip(Math.max(offset, 0))
        .limit(Math.max(limit, 0))
        .toList();
  }

  @Override
  public List<OrganizationRevision> findRevisions(OrganizationId organizationId) {
    return List.copyOf(history.getOrDefault(organizationId, List.of()));
  }

  private void appendRevision(Organization organization) {
    history
        .computeIfAbsent(organization.organizationId(), key -> new ArrayList<>())
        .add(
            new OrganizationRevision(
                organization.organizationId(),
                organization.revision(),
                organization.legalName(),
                organization.contractReference(),
                organization.feeBracket(),
                organization.status(),
                organization.updatedAt()));
  }
}
