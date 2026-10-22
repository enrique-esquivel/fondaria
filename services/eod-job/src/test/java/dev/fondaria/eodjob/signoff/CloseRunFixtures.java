package dev.fondaria.eodjob.signoff;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds the three upstream run rows a sign-off depends on, straight into {@code workflow} with raw
 * inserts — the sign-off tests care about {@code close_signoff}, not about re-driving a
 * reconciliation. Each row satisfies its table's own {@code CHECK}s ({@code V25} / {@code V27} /
 * {@code V28}).
 */
final class CloseRunFixtures {

  private static final String HEX64 = "0".repeat(64);

  private final JdbcClient jdbc;

  CloseRunFixtures(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  UUID passedInternalRun(LocalDate businessDate, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.reconciliation_run
                (run_id, business_date, status, started_at, completed_at, duration_millis)
            VALUES (:id, :date, 'PASSED', :started, :completed, 1000)
            """)
        .param("id", id)
        .param("date", businessDate)
        .param("started", Timestamp.from(at))
        .param("completed", Timestamp.from(at.plusSeconds(1)))
        .update();
    return id;
  }

  UUID passedCustodianRun(LocalDate businessDate, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_reconciliation_run
                (run_id, business_date, status, started_at, completed_at, duration_millis)
            VALUES (:id, :date, 'PASSED', :started, :completed, 1000)
            """)
        .param("id", id)
        .param("date", businessDate)
        .param("started", Timestamp.from(at))
        .param("completed", Timestamp.from(at.plusSeconds(1)))
        .update();
    return id;
  }

  UUID completedSnapshotRun(LocalDate businessDate, Instant at, long positionCount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.snapshot_run
                (run_id, business_date, status, started_at, completed_at, duration_millis,
                 position_count, fund_count, positions_object_key, positions_sha256,
                 manifest_object_key, manifest_sha256)
            VALUES (:id, :date, 'COMPLETED', :started, :completed, 1000, :count, 1,
                    :posKey, :hex, :manKey, :hex)
            """)
        .param("id", id)
        .param("date", businessDate)
        .param("started", Timestamp.from(at))
        .param("completed", Timestamp.from(at.plusSeconds(1)))
        .param("count", positionCount)
        .param("posKey", "snapshots/dt=" + businessDate + "/positions.ndjson.gz")
        .param("manKey", "snapshots/dt=" + businessDate + "/manifest.json")
        .param("hex", HEX64)
        .update();
    return id;
  }
}
