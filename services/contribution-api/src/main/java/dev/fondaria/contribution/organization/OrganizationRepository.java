package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import java.util.List;
import java.util.Optional;

/**
 * Storage for {@code master.organization} and its append-only history.
 *
 * <p>An interface, not a class, because the rules worth testing here — registration converging on
 * one organization, an update leaving the id and the ledger accounts alone, no tax identifier
 * reaching a log — are decisions {@link OrganizationService} makes, and they should be provable
 * without a database in the loop. The one JDBC implementation proves the SQL separately, against a
 * real PostgreSQL.
 */
public interface OrganizationRepository {

  /**
   * Inserts {@code organization} as revision 1 together with its first history row, unless an
   * organization already exists under its id.
   *
   * @return {@code true} if this call created the row, {@code false} if it already existed
   */
  boolean insertIfAbsent(Organization organization);

  /** Replaces the current state and appends the matching history row, atomically. */
  void update(Organization next);

  Optional<Organization> findById(OrganizationId organizationId);

  /** Registration order, oldest first, so paging through it is stable as new ones are added. */
  List<Organization> findAll(int limit, int offset);

  /** The append-only history of one organization, oldest revision first. */
  List<OrganizationRevision> findRevisions(OrganizationId organizationId);
}
