package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ParticipantRepository} over {@code master.participant}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
public class JdbcParticipantRepository implements ParticipantRepository {

  private static final String SELECT_PARTICIPANT =
      """
      SELECT organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
             participant_status, employment_status, employment_start_date, enrolment_date,
             employment_end_date, created_at, updated_at
        FROM master.participant
      """;

  private final JdbcClient jdbc;

  public JdbcParticipantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code ON CONFLICT (organization_id, participant_internal_ref) DO NOTHING} names its inference
   * specification, deliberately unlike {@code JdbcContributionBatchRepository.submit}. There the
   * two constraints that could fire mean the same fact ("this file was already submitted"); here
   * they do not. A conflict on the primary key means the participant this call is enrolling has
   * already been enrolled — matched, not an error. A conflict on the separate {@code
   * participant_payroll_ref} uniqueness means something else entirely: two different internal refs
   * are claiming the same payroll reference within one organization, which is an employer data
   * problem this call must not paper over by silently doing nothing. Naming the primary key as the
   * only suppressed conflict lets that second case raise.
   */
  @Override
  public boolean insertIfAbsent(Participant participant) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO master.participant (
                    organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                    participant_status, employment_status, employment_start_date, enrolment_date,
                    employment_end_date, created_at, updated_at)
                VALUES (:organizationId, :internalRef, :planId, :payrollRef,
                        :status, :employmentStatus, :employmentStartDate, :enrolmentDate,
                        :employmentEndDate, :createdAt, :updatedAt)
                ON CONFLICT (organization_id, participant_internal_ref) DO NOTHING
                """)
            .param("organizationId", participant.organizationId().value())
            .param("internalRef", participant.internalRef())
            .param("planId", participant.planId())
            .param("payrollRef", participant.payrollRef())
            .param("status", participant.status().name())
            .param("employmentStatus", participant.employmentStatus().name())
            .param("employmentStartDate", participant.employmentStartDate())
            .param("enrolmentDate", participant.enrolmentDate())
            .param("employmentEndDate", participant.employmentEndDate().orElse(null), Types.DATE)
            .param("createdAt", Timestamp.from(participant.createdAt()))
            .param("updatedAt", Timestamp.from(participant.updatedAt()))
            .update();
    return inserted > 0;
  }

  @Override
  public Optional<Participant> find(OrganizationId organizationId, String internalRef) {
    return jdbc.sql(
            SELECT_PARTICIPANT
                + " WHERE organization_id = :organizationId AND participant_internal_ref = :internalRef")
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .query(JdbcParticipantRepository::mapParticipant)
        .optional();
  }

  private static Participant mapParticipant(ResultSet rs, int rowNum) throws SQLException {
    return new Participant(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        rs.getString("plan_id"),
        rs.getString("participant_payroll_ref"),
        ParticipantStatus.parse(rs.getString("participant_status")),
        EmploymentStatus.parse(rs.getString("employment_status")),
        rs.getObject("employment_start_date", LocalDate.class),
        rs.getObject("enrolment_date", LocalDate.class),
        Optional.ofNullable(rs.getObject("employment_end_date", LocalDate.class)),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }
}
