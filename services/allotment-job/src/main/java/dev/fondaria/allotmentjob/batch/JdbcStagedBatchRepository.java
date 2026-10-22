package dev.fondaria.allotmentjob.batch;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StagedBatchRepository} over {@code workflow.contribution_batch} and {@code
 * workflow.contribution_batch_row}, using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no
 * ORM).
 */
@Repository
public class JdbcStagedBatchRepository implements StagedBatchRepository {

  private final JdbcClient jdbc;

  public JdbcStagedBatchRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reuses the {@code (status, submitted_at)} index Task 3.4 already built (V4) rather than adding
   * a new one: this is sweep-ordering, not the cut-off computation Task 21.1's {@code staged_at}
   * index exists for, so submission order is a perfectly good tie-breaker for "which backlog item
   * to try next".
   */
  @Override
  public List<UUID> stagedBatchIds(int limit) {
    return jdbc.sql(
            """
            SELECT batch_id
              FROM workflow.contribution_batch
             WHERE status = 'STAGED'
             ORDER BY submitted_at
             LIMIT :limit
            """)
        .param("limit", limit)
        .query((rs, rowNum) -> rs.getObject("batch_id", UUID.class))
        .list();
  }

  @Override
  public Optional<StagedBatch> lockForAllotment(UUID batchId) {
    // staged_at IS NOT NULL excludes the one gap Task 21.1's migration itself documents: a
    // batch staged before that column existed. Such a batch cannot be allotted until an
    // operator resolves it by hand — it is left STAGED rather than allotted at a dealing date
    // this run invented for it.
    return jdbc.sql(
            """
            SELECT batch_id, organization_id, plan_id, value_date, staged_at
              FROM workflow.contribution_batch
             WHERE batch_id = :batchId
               AND status = 'STAGED'
               AND staged_at IS NOT NULL
               FOR UPDATE SKIP LOCKED
            """)
        .param("batchId", batchId)
        .query(JdbcStagedBatchRepository::mapBatch)
        .optional();
  }

  @Override
  public List<StagedRow> rowsFor(UUID batchId) {
    return jdbc.sql(
            """
            SELECT organization_id, batch_id, participant_internal_ref,
                   employee_minor_units, employer_minor_units, voluntary_minor_units
              FROM workflow.contribution_batch_row
             WHERE batch_id = :batchId
             ORDER BY participant_internal_ref
            """)
        .param("batchId", batchId)
        .query(JdbcStagedBatchRepository::mapRow)
        .list();
  }

  @Override
  public boolean markAllotted(UUID batchId, Instant allottedAt) {
    return jdbc.sql(
                """
            UPDATE workflow.contribution_batch
               SET status = 'ALLOTTED',
                   status_updated_at = :allottedAt
             WHERE batch_id = :batchId
               AND status = 'STAGED'
            """)
            .param("batchId", batchId)
            .param("allottedAt", Timestamp.from(allottedAt))
            .update()
        > 0;
  }

  private static StagedBatch mapBatch(ResultSet rs, int rowNum) throws SQLException {
    return new StagedBatch(
        rs.getObject("batch_id", UUID.class),
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("plan_id"),
        rs.getObject("value_date", LocalDate.class),
        rs.getTimestamp("staged_at").toInstant());
  }

  private static StagedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    amounts.put(ContributionSource.EMPLOYEE, rs.getLong("employee_minor_units"));
    amounts.put(ContributionSource.EMPLOYER, rs.getLong("employer_minor_units"));
    amounts.put(ContributionSource.VOLUNTARY, rs.getLong("voluntary_minor_units"));
    return new StagedRow(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getObject("batch_id", UUID.class),
        rs.getString("participant_internal_ref"),
        amounts);
  }
}
