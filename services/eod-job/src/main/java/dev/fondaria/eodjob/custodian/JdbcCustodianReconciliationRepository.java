package dev.fondaria.eodjob.custodian;

import dev.fondaria.eodjob.reconciliation.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CustodianReconciliationRepository} over {@code workflow.custodian_reconciliation_run}
 * ({@code V27}), using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM). Mirrors {@code
 * JdbcReconciliationRepository}'s shape for the same reasons: opened {@code RUNNING} before any
 * comparison, and the guard {@code WHERE status = 'RUNNING'} on completion catches a run settled by
 * something else while this one was still comparing.
 */
@Repository
public class JdbcCustodianReconciliationRepository implements CustodianReconciliationRepository {

  private final JdbcClient jdbc;

  public JdbcCustodianReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_reconciliation_run (run_id, business_date, status, started_at)
            VALUES (:runId, :businessDate, 'RUNNING', :startedAt)
            """)
        .param("runId", runId)
        .param("businessDate", businessDate)
        .param("startedAt", Timestamp.from(startedAt))
        .update();
  }

  @Override
  public void complete(CustodianReconciliationRun run) {
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.custodian_reconciliation_run
                   SET status = :status,
                       completed_at = :completedAt,
                       duration_millis = :durationMillis,
                       provider_account_count = :providerAccountCount,
                       statements_received_count = :statementsReceivedCount,
                       missing_statement_count = :missingStatementCount,
                       real_break_count = :realBreakCount,
                       timing_difference_count = :timingDifferenceCount
                 WHERE run_id = :runId
                   AND status = 'RUNNING'
                """)
            .param("status", run.status().name())
            .param("completedAt", Timestamp.from(run.completedAt().orElseThrow()))
            .param("durationMillis", run.durationMillis().orElseThrow())
            .param("providerAccountCount", run.providerAccountCount())
            .param("statementsReceivedCount", run.statementsReceivedCount())
            .param("missingStatementCount", run.missingStatementCount())
            .param("realBreakCount", run.realBreakCount())
            .param("timingDifferenceCount", run.timingDifferenceCount())
            .param("runId", run.runId())
            .update();
    if (updated != 1) {
      throw new IllegalStateException(
          "custodian reconciliation run "
              + run.runId()
              + " was no longer RUNNING when it completed");
    }
  }

  @Override
  public void fail(UUID runId, Instant failedAt, long durationMillis) {
    jdbc.sql(
            """
            UPDATE workflow.custodian_reconciliation_run
               SET status = 'FAILED',
                   completed_at = :failedAt,
                   duration_millis = :durationMillis
             WHERE run_id = :runId
               AND status = 'RUNNING'
            """)
        .param("failedAt", Timestamp.from(failedAt))
        .param("durationMillis", durationMillis)
        .param("runId", runId)
        .update();
  }

  @Override
  public Optional<CustodianReconciliationRun> latestFor(LocalDate businessDate) {
    return jdbc.sql(
            """
            SELECT run_id, business_date, status, started_at, completed_at, duration_millis,
                   provider_account_count, statements_received_count, missing_statement_count,
                   real_break_count, timing_difference_count
              FROM workflow.custodian_reconciliation_run
             WHERE business_date = :businessDate
             ORDER BY completed_at DESC NULLS LAST, started_at DESC
             LIMIT 1
            """)
        .param("businessDate", businessDate)
        .query(JdbcCustodianReconciliationRepository::mapRun)
        .optional();
  }

  private static CustodianReconciliationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    Timestamp completedAt = rs.getTimestamp("completed_at");
    long durationMillis = rs.getLong("duration_millis");
    boolean durationPresent = !rs.wasNull();
    return new CustodianReconciliationRun(
        rs.getObject("run_id", UUID.class),
        rs.getObject("business_date", LocalDate.class),
        RunStatus.parse(rs.getString("status")),
        rs.getTimestamp("started_at").toInstant(),
        Optional.ofNullable(completedAt).map(Timestamp::toInstant),
        durationPresent ? Optional.of(durationMillis) : Optional.empty(),
        rs.getInt("provider_account_count"),
        rs.getInt("statements_received_count"),
        rs.getInt("missing_statement_count"),
        rs.getInt("real_break_count"),
        rs.getInt("timing_difference_count"));
  }
}
