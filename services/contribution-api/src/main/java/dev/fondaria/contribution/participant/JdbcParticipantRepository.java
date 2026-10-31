package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

  /**
   * Rows per {@code INSERT}. Eleven bind parameters a row keeps a statement at 11,000 parameters,
   * far under the driver's bind limit (32,767 in older pgjdbc, 65,535 in PostgreSQL).
   */
  static final int ROWS_PER_STATEMENT = 1_000;

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
   *
   * <p>One multi-row {@code INSERT} per {@link #ROWS_PER_STATEMENT} participants, the pattern
   * {@code JdbcAllotmentRepository.recordAll} uses. Each row's {@code ON CONFLICT} still applies on
   * its own, and the row count PostgreSQL reports is exactly the rows inserted. A payroll-ref
   * conflict fails the whole statement it is in, so none of that statement's rows land.
   */
  @Override
  public int insertAllIfAbsent(List<Participant> participants) {
    int inserted = 0;
    for (int from = 0; from < participants.size(); from += ROWS_PER_STATEMENT) {
      inserted +=
          insertChunk(
              participants.subList(from, Math.min(participants.size(), from + ROWS_PER_STATEMENT)));
    }
    return inserted;
  }

  private int insertChunk(List<Participant> chunk) {
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                employment_end_date, created_at, updated_at)
            VALUES
            """);
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < chunk.size(); i++) {
      Participant participant = chunk.get(i);
      if (i > 0) {
        sql.append(",\n");
      }
      sql.append(
          String.format(
              Locale.ROOT,
              "(:organizationId%1$d, :internalRef%1$d, :planId%1$d, :payrollRef%1$d,"
                  + " :status%1$d, :employmentStatus%1$d, :employmentStartDate%1$d,"
                  + " :enrolmentDate%1$d, :employmentEndDate%1$d, :createdAt%1$d, :updatedAt%1$d)",
              i));
      params.addValue("organizationId" + i, participant.organizationId().value());
      params.addValue("internalRef" + i, participant.internalRef());
      params.addValue("planId" + i, participant.planId());
      params.addValue("payrollRef" + i, participant.payrollRef());
      params.addValue("status" + i, participant.status().name());
      params.addValue("employmentStatus" + i, participant.employmentStatus().name());
      params.addValue("employmentStartDate" + i, participant.employmentStartDate());
      params.addValue("enrolmentDate" + i, participant.enrolmentDate());
      params.addValue(
          "employmentEndDate" + i, participant.employmentEndDate().orElse(null), Types.DATE);
      params.addValue("createdAt" + i, Timestamp.from(participant.createdAt()));
      params.addValue("updatedAt" + i, Timestamp.from(participant.updatedAt()));
    }
    sql.append("\nON CONFLICT (organization_id, participant_internal_ref) DO NOTHING");
    return jdbc.sql(sql.toString()).paramSource(params).update();
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
