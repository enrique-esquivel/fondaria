package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

  /**
   * Rows per {@code INSERT}. Eleven bind parameters a row keeps a statement at 11,000 parameters,
   * far under the driver's bind limit (32,767 in older pgjdbc, 65,535 in PostgreSQL).
   */
  static final int ROWS_PER_STATEMENT = 1_000;

  private final JdbcClient jdbc;

  public JdbcStagedRowRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code ON CONFLICT (organization_id, batch_id, participant_internal_ref) DO NOTHING} names its
   * inference specification: the primary key is the only constraint this table has, and a conflict
   * on it means exactly one thing — this row has already been captured for this batch — so
   * re-running structural validation after a crash converges rather than failing.
   *
   * <p>One multi-row {@code INSERT} per {@link #ROWS_PER_STATEMENT} rows rather than one per row,
   * the pattern {@code JdbcAllotmentRepository.recordAll} uses; each row's {@code ON CONFLICT}
   * still applies on its own.
   */
  @Override
  public int insertAllIfAbsent(List<StagedRow> rows) {
    int inserted = 0;
    for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
      inserted += insertChunk(rows.subList(from, Math.min(rows.size(), from + ROWS_PER_STATEMENT)));
    }
    return inserted;
  }

  private int insertChunk(List<StagedRow> chunk) {
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO workflow.contribution_batch_row (
                organization_id, batch_id, participant_internal_ref, participant_payroll_ref,
                plan_id, payroll_period, value_date,
                employee_minor_units, employer_minor_units, voluntary_minor_units, recorded_at)
            VALUES
            """);
    Map<String, Object> params = new HashMap<>();
    for (int i = 0; i < chunk.size(); i++) {
      StagedRow row = chunk.get(i);
      if (i > 0) {
        sql.append(",\n");
      }
      sql.append(
          String.format(
              Locale.ROOT,
              "(:organizationId%1$d, :batchId%1$d, :internalRef%1$d, :payrollRef%1$d,"
                  + " :planId%1$d, :payrollPeriod%1$d, :valueDate%1$d, :employeeMinorUnits%1$d,"
                  + " :employerMinorUnits%1$d, :voluntaryMinorUnits%1$d, :recordedAt%1$d)",
              i));
      params.put("organizationId" + i, row.organizationId().value());
      params.put("batchId" + i, row.batchId());
      params.put("internalRef" + i, row.participantInternalRef());
      params.put("payrollRef" + i, row.participantPayrollRef());
      params.put("planId" + i, row.planId());
      params.put("payrollPeriod" + i, row.payrollPeriod());
      params.put("valueDate" + i, row.valueDate());
      params.put("employeeMinorUnits" + i, row.amountOf(ContributionSource.EMPLOYEE));
      params.put("employerMinorUnits" + i, row.amountOf(ContributionSource.EMPLOYER));
      params.put("voluntaryMinorUnits" + i, row.amountOf(ContributionSource.VOLUNTARY));
      params.put("recordedAt" + i, Timestamp.from(row.recordedAt()));
    }
    sql.append("\nON CONFLICT (organization_id, batch_id, participant_internal_ref) DO NOTHING");
    return jdbc.sql(sql.toString()).params(params).update();
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
