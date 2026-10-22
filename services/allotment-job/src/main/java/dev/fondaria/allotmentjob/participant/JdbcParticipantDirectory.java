package dev.fondaria.allotmentjob.participant;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ParticipantDirectory} over {@code master.participant}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <p>Read-only: contribution-api owns this table's writes (auto-enrolment, Task 2b.3). This service
 * only ever resolves a participant's plan and employment start date to compute a vesting schedule
 * (Task 21.4).
 */
@Repository
public class JdbcParticipantDirectory implements ParticipantDirectory {

  private final JdbcClient jdbc;

  public JdbcParticipantDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<VestingParticipant> find(
      OrganizationId organizationId, String participantInternalRef) {
    return jdbc.sql(
            """
            SELECT plan_id, employment_start_date
              FROM master.participant
             WHERE organization_id = :organizationId
               AND participant_internal_ref = :participantInternalRef
            """)
        .param("organizationId", organizationId.value())
        .param("participantInternalRef", participantInternalRef)
        .query(JdbcParticipantDirectory::mapRow)
        .optional();
  }

  private static VestingParticipant mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new VestingParticipant(
        rs.getString("plan_id"), rs.getObject("employment_start_date", LocalDate.class));
  }
}
