package dev.fondaria.allotmentjob.allotment;

import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AllotmentResidualRepository} over {@code workflow.allotment_residual} (V17), using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@code ON CONFLICT} names the primary key explicitly, the same discipline {@code
 * JdbcAllotmentRepository}'s javadoc records: an unqualified clause would suppress a genuinely
 * different conflict — one that should never happen here — instead of surfacing it.
 */
@Repository
public class JdbcAllotmentResidualRepository implements AllotmentResidualRepository {

  private final JdbcClient jdbc;

  public JdbcAllotmentResidualRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(AllotmentResidual residual) {
    jdbc.sql(
            """
            INSERT INTO workflow.allotment_residual (
                batch_id, organization_id, fund_id, nav_version_id, row_count,
                residual_minor_units, recorded_at)
            VALUES (:batchId, :organizationId, :fundId, :navVersionId, :rowCount,
                    :residualMinorUnits, :recordedAt)
            ON CONFLICT ON CONSTRAINT allotment_residual_pk DO NOTHING
            """)
        .param("batchId", residual.batchId())
        .param("organizationId", residual.organizationId().value())
        .param("fundId", residual.fundId().value())
        .param("navVersionId", residual.navVersionId())
        .param("rowCount", residual.rowCount())
        .param("residualMinorUnits", residual.residual().cents())
        .param("recordedAt", Timestamp.from(residual.recordedAt()))
        .update();
  }
}
