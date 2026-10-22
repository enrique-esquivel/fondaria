package dev.fondaria.statementworker.render;

import dev.fondaria.common.ParticipantId;
import dev.fondaria.statementworker.ParticipantKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ParticipantDirectory} over {@code master.participant}'s primary key ({@code
 * organization_id, participant_internal_ref}), using {@code JdbcClient} (docs/ENGINEERING-RULES.md
 * §5 — no ORM). The lookup is served by that primary key directly — no secondary index needed.
 */
@Repository
public class JdbcParticipantDirectory implements ParticipantDirectory {

  private static final String SELECT_PARTICIPANT_ID =
      """
      SELECT participant_id
        FROM master.participant
       WHERE organization_id = :organizationId
         AND participant_internal_ref = :participantInternalRef
      """;

  private final JdbcClient jdbc;

  public JdbcParticipantDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ParticipantId> find(ParticipantKey key) {
    return jdbc.sql(SELECT_PARTICIPANT_ID)
        .param("organizationId", key.organizationId().value())
        .param("participantInternalRef", key.participantInternalRef())
        .query((rs, rowNum) -> ParticipantId.of(rs.getObject("participant_id", UUID.class)))
        .optional();
  }
}
