package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.util.List;
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
  default boolean insertIfAbsent(Participant participant) {
    return insertAllIfAbsent(List.of(participant)) == 1;
  }

  /**
   * {@link #insertIfAbsent} for many participants at once, with the same per-row idempotency: each
   * participant already present is matched, never re-created or updated.
   *
   * <p>Exists because enrolment is a round trip per participant otherwise, and on a cluster whose
   * PostgreSQL acknowledges a commit only after standbys in other zones do, a round trip and a
   * commit per row is what bounds a 300,000-row file (GKE load run, 2026-10-30: ~120 rows/s on an
   * idle cluster). Atomic per call only as far as the implementation says: the JDBC one commits
   * each statement on its own when no transaction is open.
   *
   * @return how many participants this call created
   */
  int insertAllIfAbsent(List<Participant> participants);

  Optional<Participant> find(OrganizationId organizationId, String internalRef);
}
