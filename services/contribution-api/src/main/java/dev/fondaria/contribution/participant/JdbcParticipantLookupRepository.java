package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ParticipantLookupRepository} over {@code master.participant}, using {@code JdbcClient}.
 */
@Repository
public class JdbcParticipantLookupRepository implements ParticipantLookupRepository {

  private static final String SELECT_LOOKUP =
      """
      SELECT participant_id, participant_internal_ref, participant_payroll_ref
        FROM master.participant
      """;

  private final JdbcClient jdbc;

  public JdbcParticipantLookupRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ParticipantLookup> findByInternalRef(
      OrganizationId organizationId, String internalRef) {
    return jdbc.sql(
            SELECT_LOOKUP
                + " WHERE organization_id = :organizationId AND participant_internal_ref = :ref")
        .param("organizationId", organizationId.value())
        .param("ref", internalRef)
        .query(JdbcParticipantLookupRepository::mapLookup)
        .optional();
  }

  @Override
  public Optional<ParticipantLookup> findByPayrollRef(
      OrganizationId organizationId, String payrollRef) {
    return jdbc.sql(
            SELECT_LOOKUP
                + " WHERE organization_id = :organizationId AND participant_payroll_ref = :ref")
        .param("organizationId", organizationId.value())
        .param("ref", payrollRef)
        .query(JdbcParticipantLookupRepository::mapLookup)
        .optional();
  }

  @Override
  public Optional<ParticipantLookup> findByParticipantId(
      OrganizationId organizationId, ParticipantId participantId) {
    return jdbc.sql(
            SELECT_LOOKUP
                + " WHERE organization_id = :organizationId AND participant_id = :participantId")
        .param("organizationId", organizationId.value())
        .param("participantId", participantId.value())
        .query(JdbcParticipantLookupRepository::mapLookup)
        .optional();
  }

  private static ParticipantLookup mapLookup(ResultSet rs, int rowNum) throws SQLException {
    return new ParticipantLookup(
        ParticipantId.of(rs.getObject("participant_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        rs.getString("participant_payroll_ref"));
  }
}
