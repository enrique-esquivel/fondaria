package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.FundId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReconciliationRepository} over {@code workflow.reconciliation_run} and {@code
 * workflow.reconciliation_break} ({@code V25}), using {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p>{@link #complete} is one transaction over both tables. A run marked {@code FAILED} whose
 * breaks had not been written would name a drift nobody could see, and a run whose breaks were
 * written but whose status never settled would leave the gate blocking on a pass that had in fact
 * finished. Neither is a state an operator should ever have to reason about, so neither is
 * reachable.
 *
 * <p>The status column is the one thing here that is updated in place — the {@code workflow}
 * schema's own lifecycle rule (ADR-0011), and the same shape {@code workflow.contribution_batch}
 * uses. Breaks are never updated: {@code V25} revokes {@code UPDATE} and {@code DELETE} on them.
 */
@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {

  private final JdbcClient jdbc;

  public JdbcReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
    jdbc.sql(
            """
            INSERT INTO workflow.reconciliation_run (run_id, business_date, status, started_at)
            VALUES (:runId, :businessDate, 'RUNNING', :startedAt)
            """)
        .param("runId", runId)
        .param("businessDate", businessDate)
        .param("startedAt", Timestamp.from(startedAt))
        .update();
  }

  @Override
  @Transactional
  public void complete(ReconciliationRun run, List<ReconciliationBreak> breaks) {
    int breakNo = 0;
    for (ReconciliationBreak raised : breaks) {
      insertBreak(run, ++breakNo, raised);
    }
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.reconciliation_run
                   SET status = :status,
                       completed_at = :completedAt,
                       duration_millis = :durationMillis,
                       fund_count = :fundCount,
                       holding_account_count = :holdingAccountCount,
                       cash_account_count = :cashAccountCount,
                       break_count = :breakCount
                 WHERE run_id = :runId
                   AND status = 'RUNNING'
                """)
            .param("status", run.status().name())
            .param("completedAt", Timestamp.from(run.completedAt().orElseThrow()))
            .param("durationMillis", run.durationMillis().orElseThrow())
            .param("fundCount", run.fundCount())
            .param("holdingAccountCount", run.holdingAccountCount())
            .param("cashAccountCount", run.cashAccountCount())
            .param("breakCount", run.breakCount())
            .param("runId", run.runId())
            .update();
    if (updated != 1) {
      // The guard is WHERE status = 'RUNNING'. Failing it means something settled this run while
      // it was still comparing, and overwriting that would hide whatever did.
      throw new IllegalStateException(
          "reconciliation run " + run.runId() + " was no longer RUNNING when it completed");
    }
  }

  @Override
  public void fail(UUID runId, Instant failedAt, long durationMillis) {
    jdbc.sql(
            """
            UPDATE workflow.reconciliation_run
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
  public Optional<ReconciliationRun> latestFor(LocalDate businessDate) {
    return jdbc.sql(
            """
            SELECT run_id, business_date, status, started_at, completed_at, duration_millis,
                   fund_count, holding_account_count, cash_account_count, break_count
              FROM workflow.reconciliation_run
             WHERE business_date = :businessDate
             ORDER BY completed_at DESC NULLS LAST, started_at DESC
             LIMIT 1
            """)
        .param("businessDate", businessDate)
        .query(JdbcReconciliationRepository::mapRun)
        .optional();
  }

  @Override
  public List<ReconciliationBreak> breaksOf(UUID runId) {
    return jdbc.sql(
            """
            SELECT scope, fund_id, unit_ledger_id, account_id::text AS account_id,
                   left_derivation, left_amount_scaled, right_derivation, right_amount_scaled
              FROM workflow.reconciliation_break
             WHERE run_id = :runId
             ORDER BY break_no
            """)
        .param("runId", runId)
        .query(JdbcReconciliationRepository::mapBreak)
        .list();
  }

  private void insertBreak(ReconciliationRun run, int breakNo, ReconciliationBreak raised) {
    jdbc.sql(
            """
            INSERT INTO workflow.reconciliation_break (
                run_id, break_no, scope, fund_id, unit_ledger_id, account_id,
                left_derivation, left_amount_scaled, right_derivation, right_amount_scaled,
                drift_scaled, detected_at)
            VALUES (
                :runId, :breakNo, :scope, :fundId, :unitLedgerId, CAST(:accountId AS numeric),
                :leftDerivation, :leftAmount, :rightDerivation, :rightAmount,
                :drift, :detectedAt)
            """)
        .param("runId", run.runId())
        .param("breakNo", breakNo)
        .param("scope", raised.scope().name())
        .param("fundId", raised.fundId().map(FundId::value).orElse(null), Types.OTHER)
        .param("unitLedgerId", raised.unitLedgerId().orElse(null), Types.BIGINT)
        .param("accountId", raised.accountId().orElse(null), Types.VARCHAR)
        .param("leftDerivation", raised.left().name())
        .param("leftAmount", raised.leftAmountScaled())
        .param("rightDerivation", raised.right().name())
        .param("rightAmount", raised.rightAmountScaled())
        .param("drift", raised.driftScaled())
        .param("detectedAt", Timestamp.from(run.completedAt().orElseThrow()))
        .update();
  }

  private static ReconciliationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    Timestamp completedAt = rs.getTimestamp("completed_at");
    long durationMillis = rs.getLong("duration_millis");
    boolean durationPresent = !rs.wasNull();
    return new ReconciliationRun(
        rs.getObject("run_id", UUID.class),
        rs.getObject("business_date", LocalDate.class),
        RunStatus.parse(rs.getString("status")),
        rs.getTimestamp("started_at").toInstant(),
        Optional.ofNullable(completedAt).map(Timestamp::toInstant),
        durationPresent ? Optional.of(durationMillis) : Optional.empty(),
        rs.getInt("fund_count"),
        rs.getLong("holding_account_count"),
        rs.getInt("cash_account_count"),
        rs.getInt("break_count"));
  }

  private static ReconciliationBreak mapBreak(ResultSet rs, int rowNum) throws SQLException {
    UUID fundId = rs.getObject("fund_id", UUID.class);
    long unitLedgerId = rs.getLong("unit_ledger_id");
    boolean unitLedgerPresent = !rs.wasNull();
    // account_id::text in the query above, not getBigDecimal here: the column is numeric(39,0)
    // holding a u128, which is an identifier and never arithmetic. AGENTS.md §4 confines
    // BigDecimal to the display boundary, and reading one only to call toString() on it would
    // put the type in a path that has no numbers in it.
    String accountId = rs.getString("account_id");
    return new ReconciliationBreak(
        BreakScope.valueOf(rs.getString("scope")),
        Optional.ofNullable(fundId).map(FundId::new),
        unitLedgerPresent ? Optional.of(unitLedgerId) : Optional.empty(),
        Optional.ofNullable(accountId),
        Derivation.valueOf(rs.getString("left_derivation")),
        rs.getLong("left_amount_scaled"),
        Derivation.valueOf(rs.getString("right_derivation")),
        rs.getLong("right_amount_scaled"));
  }
}
