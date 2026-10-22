package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.Optional;

/**
 * Resolves {@code master.participant.participant_id} — the id every Phase 6 read endpoint is keyed
 * by — from either reference a caller might already hold. A separate interface from {@link
 * ParticipantRepository} rather than an added method on it: that interface's one job is enrolment's
 * idempotency (Task 2b.3), provable without a database in the loop, and it does not select {@code
 * participant_id} at all today. This is a plain read with nothing to decide, so one JDBC
 * implementation is the whole of it.
 */
public interface ParticipantLookupRepository {

  Optional<ParticipantLookup> findByInternalRef(OrganizationId organizationId, String internalRef);

  Optional<ParticipantLookup> findByPayrollRef(OrganizationId organizationId, String payrollRef);

  /**
   * The reverse of the two lookups above — Task 9.5's identity endpoints hold only the external
   * {@code participantId} a URL carries and need {@code participant_internal_ref} to key {@code
   * master.participant_identity}.
   */
  Optional<ParticipantLookup> findByParticipantId(
      OrganizationId organizationId, ParticipantId participantId);
}
