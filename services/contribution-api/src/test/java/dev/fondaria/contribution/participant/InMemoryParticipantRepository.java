package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ParticipantRepository}, so what {@link ParticipantEnrolmentService} decides —
 * that a participant already seen is matched rather than duplicated or updated — is provable
 * without a database in the loop. The one JDBC implementation proves the SQL separately, against a
 * real PostgreSQL, in {@code JdbcParticipantRepositoryIntegrationTest}.
 *
 * <p>Public rather than package-private: {@code BatchValidationRunnerTest} needs it too, to prove
 * where in the sweep enrolment actually runs.
 */
public final class InMemoryParticipantRepository implements ParticipantRepository {

  private final Map<String, Participant> participants = new LinkedHashMap<>();

  @Override
  public int insertAllIfAbsent(List<Participant> batch) {
    int inserted = 0;
    for (Participant participant : batch) {
      if (participants.putIfAbsent(
              key(participant.organizationId(), participant.internalRef()), participant)
          == null) {
        inserted++;
      }
    }
    return inserted;
  }

  @Override
  public Optional<Participant> find(OrganizationId organizationId, String internalRef) {
    return Optional.ofNullable(participants.get(key(organizationId, internalRef)));
  }

  public int size() {
    return participants.size();
  }

  private static String key(OrganizationId organizationId, String internalRef) {
    return organizationId.value() + "|" + internalRef;
  }
}
