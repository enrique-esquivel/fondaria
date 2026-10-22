package dev.fondaria.contribution.participant.identity;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.Optional;

/**
 * Storage for {@code master.participant_identity}.
 *
 * <p>An interface for the same reason {@code ParticipantRepository} is one: {@link
 * ParticipantIdentityService} owns the decision of when a fresh data key is generated and what
 * "already shredded" means, and should be provable without a database in the loop. The one JDBC
 * implementation proves the SQL separately, against a real PostgreSQL.
 */
public interface ParticipantIdentityRepository {

  /**
   * Inserts or wholly replaces the identity row for {@code (organizationId, internalRef)} —
   * captured identity data is corrected by resubmission, not by a partial update, so every call
   * carries a complete, freshly encrypted set of fields under a freshly generated data key.
   */
  void upsert(ParticipantIdentity identity);

  Optional<ParticipantIdentity> find(OrganizationId organizationId, String internalRef);

  /**
   * Crypto-shreds the participant's data key: sets {@code wrapped_data_key} to {@code NULL} and
   * records {@code destroyedAt}. A no-op returning {@code false} if no row exists, or if the key
   * was already destroyed — destroying twice is not an error.
   */
  boolean destroyKey(OrganizationId organizationId, String internalRef, Instant destroyedAt);
}
