package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StagedRowRepository} over {@code workflow.contribution_batch_row}, using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
public class JdbcStagedRowRepository implements StagedRowRepository {

  private static final String SELECT_ROW =
      """
      SELECT r.organization_id, r.batch_id, r.participant_internal_ref, r.participant_payroll_ref,
             r.plan_id, r.payroll_period, r.value_date,
             r.employee_minor_units, r.employer_minor_units, r.voluntary_minor_units,
             r.recorded_at
        FROM workflow.contribution_batch_row r
      """;

  private final JdbcClient jdbc;

  public JdbcStagedRowRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code ON CONFLICT (organization_id, batch_id, participant_internal_ref) DO NOTHING} names its
   * inference specification: the primary key is the only constraint this table has, and a conflict
   * on it means exactly one thing — this row has already been captured for this batch — so
   * re-running structural validation after a crash converges rather than failing.
   */
  @Override
  public boolean insertIfAbsent(StagedRow row) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO workflow.contribution_batch_row (
                    organization_id, batch_id, participant_internal_ref, participant_payroll_ref,
                    plan_id, payroll_period, value_date,
                    employee_minor_units, employer_minor_units, voluntary_minor_units, recorded_at)
                VALUES (:organizationId, :batchId, :internalRef, :payrollRef,
                        :planId, :payrollPeriod, :valueDate,
                        :employeeMinorUnits, :employerMinorUnits, :voluntaryMinorUnits, :recordedAt)
                ON CONFLICT (organization_id, batch_id, participant_internal_ref) DO NOTHING
                """)
            .param("organizationId", row.organizationId().value())
            .param("batchId", row.batchId())
            .param("internalRef", row.participantInternalRef())
            .param("payrollRef", row.participantPayrollRef())
            .param("planId", row.planId())
            .param("payrollPeriod", row.payrollPeriod())
            .param("valueDate", row.valueDate())
            .param("employeeMinorUnits", row.amountOf(ContributionSource.EMPLOYEE))
            .param("employerMinorUnits", row.amountOf(ContributionSource.EMPLOYER))
            .param("voluntaryMinorUnits", row.amountOf(ContributionSource.VOLUNTARY))
            .param("recordedAt", Timestamp.from(row.recordedAt()))
            .update();
    return inserted > 0;
  }

  @Override
  public List<StagedRow> findByBatch(OrganizationId organizationId, UUID batchId) {
    return jdbc.sql(
            SELECT_ROW
                + " WHERE r.organization_id = :organizationId AND r.batch_id = :batchId"
                + " ORDER BY r.participant_internal_ref")
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .query(JdbcStagedRowRepository::mapRow)
        .list();
  }

  @Override
  public List<StagedRow> find(
      OrganizationId organizationId, String payrollPeriod, ContributionBatchStatus status) {
    return jdbc.sql(
            SELECT_ROW
                + """
                  JOIN workflow.contribution_batch b ON b.batch_id = r.batch_id
                 WHERE r.organization_id = :organizationId
                   AND b.payroll_period = :payrollPeriod
                   AND b.status = :status
                 ORDER BY r.batch_id, r.participant_internal_ref
                """)
        .param("organizationId", organizationId.value())
        .param("payrollPeriod", payrollPeriod)
        .param("status", status.name())
        .query(JdbcStagedRowRepository::mapRow)
        .list();
  }

  private static StagedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new StagedRow(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getObject("batch_id", UUID.class),
        rs.getString("participant_internal_ref"),
        rs.getString("participant_payroll_ref"),
        rs.getString("plan_id"),
        rs.getString("payroll_period"),
        rs.getObject("value_date", LocalDate.class),
        Map.of(
            ContributionSource.EMPLOYEE,
            rs.getLong("employee_minor_units"),
            ContributionSource.EMPLOYER,
            rs.getLong("employer_minor_units"),
            ContributionSource.VOLUNTARY,
            rs.getLong("voluntary_minor_units")),
        rs.getTimestamp("recorded_at").toInstant());
  }
}
