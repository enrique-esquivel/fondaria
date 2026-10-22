package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.util.Optional;

/**
 * Storage for {@code master.participant}.
 *
 * <p>An interface for the same reason {@code OrganizationRepository} is one: the decision worth
 * testing — that auto-enrolment matches an already-seen participant instead of duplicating or
 * updating it — belongs to {@link ParticipantEnrolmentService} and should be provable without a
 * database in the loop. The one JDBC implementation proves the SQL separately, against a real
 * PostgreSQL.
 */
public interface ParticipantRepository {

  /**
   * Inserts {@code participant} unless one already exists under {@code (organizationId,
   * internalRef)}.
   *
   * <p><b>This is the whole of auto-enrolment's idempotency.</b> A participant already seen is
   * matched, never re-created and never updated — re-sweeping a batch, or the same participant
   * reappearing in a later one, enrols nobody twice. It also means an existing participant's
   * employment dates are never rewritten by a later file.
   *
   * @return {@code true} if this call created the row, {@code false} if it already existed
   */
  boolean insertIfAbsent(Participant participant);

  Optional<Participant> find(OrganizationId organizationId, String internalRef);
}
