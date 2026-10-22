package dev.fondaria.balanceapi.participant;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ParticipantDirectory} over {@code master.participant}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <p>Served by {@code participant_id_unique} ({@code V22__participant_id.sql}): an index lookup,
 * not a scan, because this sits in front of every balance read. It is also the only PostgreSQL
 * statement the balance path can issue at all — see {@link CachingParticipantDirectory}, which is
 * what keeps it off the hot path in steady state.
 *
 * <p>Not a {@code @Repository}: it is always wrapped by {@link CachingParticipantDirectory}, and
 * two beans of the same interface would leave the injection point ambiguous. {@code
 * BalanceConfiguration} builds the pair explicitly.
 */
public class JdbcParticipantDirectory implements ParticipantDirectory {

  private static final String SELECT_SCOPE =
      """
      SELECT organization_id, participant_internal_ref
        FROM master.participant
       WHERE participant_id = :participantId
      """;

  private final JdbcClient jdbc;

  public JdbcParticipantDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ParticipantScope> find(ParticipantId participantId) {
    return jdbc.sql(SELECT_SCOPE)
        .param("participantId", participantId.value())
        .query(
            (rs, rowNum) ->
                new ParticipantScope(
                    new OrganizationId(rs.getObject("organization_id", UUID.class)),
                    rs.getString("participant_internal_ref")))
        .optional();
  }
}
