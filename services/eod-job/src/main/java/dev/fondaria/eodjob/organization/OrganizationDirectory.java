package dev.fondaria.eodjob.organization;

import dev.fondaria.common.OrganizationId;
import java.util.List;

/**
 * Read-only access to the tenant list, {@code master.organization} (Task 2b.1).
 *
 * <p>The close needs it for one thing: every organization holds an {@code EMPLOYER_COLLECTIONS}
 * cash account, and that account is part of the cash tie-out. There is no other query, so this port
 * has no other method — the same narrowing every cross-service directory in this platform applies
 * (see allotment-job's {@code ParticipantDirectory}).
 */
public interface OrganizationDirectory {

  /** Every registered organization, in a stable order so two runs report breaks the same way. */
  List<OrganizationId> allOrganizations();
}
