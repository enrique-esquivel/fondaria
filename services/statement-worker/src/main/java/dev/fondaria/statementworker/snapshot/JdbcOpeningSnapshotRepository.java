package dev.fondaria.statementworker.snapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link OpeningSnapshotRepository} over {@code workflow.snapshot_run} ({@code V28}, Task 7.4),
 * using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>Same query {@code eod-job}'s own {@code SnapshotRepository#latestCompletedBefore} runs, served
 * by the same {@code snapshot_run_by_date} index — reimplemented here rather than shared, because
 * services do not depend on each other's source trees ({@code ADR-0021}'s consequence for {@code
 * SignoffGate}, restated for this read).
 */
@Repository
public class JdbcOpeningSnapshotRepository implements OpeningSnapshotRepository {

  private final JdbcClient jdbc;

  public JdbcOpeningSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<OpeningSnapshot> latestCompletedBefore(LocalDate firstDayOfMonth) {
    return jdbc.sql(
            """
            SELECT business_date, positions_object_key
              FROM workflow.snapshot_run
             WHERE business_date < :firstDayOfMonth
               AND status = 'COMPLETED'
             ORDER BY business_date DESC, completed_at DESC, started_at DESC
             LIMIT 1
            """)
        .param("firstDayOfMonth", firstDayOfMonth)
        .query(
            (rs, rowNum) ->
                new OpeningSnapshot(
                    rs.getObject("business_date", LocalDate.class),
                    rs.getString("positions_object_key")))
        .optional();
  }

  @Override
  public Instant startedAtOf(UUID runId) {
    return jdbc.sql("SELECT started_at FROM workflow.snapshot_run WHERE run_id = :runId")
        .param("runId", runId)
        .query((rs, rowNum) -> rs.getTimestamp("started_at").toInstant())
        .optional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "close.signed-off.v1 names a snapshot run that workflow.snapshot_run has no"
                        + " row for"));
  }
}
