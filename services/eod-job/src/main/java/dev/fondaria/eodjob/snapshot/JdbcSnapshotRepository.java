package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SnapshotRepository} over {@code workflow.snapshot_run} and {@code
 * workflow.snapshot_fund_total} ({@code V28}), using {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p>{@link #complete} is one transaction over both tables, for the reason {@code
 * JdbcReconciliationRepository#complete} is: a run marked {@code COMPLETED} whose fund totals had
 * not been written would claim a tie with no evidence behind it, and totals written under a run
 * that never settled would be evidence for a snapshot nobody took.
 *
 * <p>The run's status is the one thing updated in place — the {@code workflow} schema's own
 * lifecycle rule (ADR-0011). Fund totals are never updated: {@code V28} revokes {@code UPDATE} and
 * {@code DELETE} and backs it with a trigger that raises for every role.
 */
@Repository
public class JdbcSnapshotRepository implements SnapshotRepository {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSnapshotRepository.class);

  private static final String SELECT_RUN =
      """
      SELECT run_id, business_date, status, started_at, completed_at, duration_millis,
             position_count, fund_count, positions_object_key, positions_sha256,
             manifest_object_key, manifest_sha256, previous_manifest_sha256, failure_reason
        FROM workflow.snapshot_run
      """;

  private final JdbcClient jdbc;

  public JdbcSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
    jdbc.sql(
            """
            INSERT INTO workflow.snapshot_run (run_id, business_date, status, started_at)
            VALUES (:runId, :businessDate, 'RUNNING', :startedAt)
            """)
        .param("runId", runId)
        .param("businessDate", businessDate)
        .param("startedAt", Timestamp.from(startedAt))
        .update();
  }

  @Override
  public void recordIntent(UUID runId, String positionsObjectKey, String positionsSha256) {
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.snapshot_run
                   SET positions_object_key = :positionsObjectKey,
                       positions_sha256 = :positionsSha256
                 WHERE run_id = :runId
                   AND status = 'RUNNING'
                """)
            .param("positionsObjectKey", positionsObjectKey)
            .param("positionsSha256", positionsSha256)
            .param("runId", runId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException(
          "snapshot run " + runId + " was no longer RUNNING when it recorded what it would store");
    }
  }

  @Override
  @Transactional
  public void complete(SnapshotRun run, List<SnapshotFundTotal> fundTotals) {
    // The guarded UPDATE first, then the totals — the opposite order to
    // JdbcReconciliationRepository#complete, and deliberately. Both are one transaction, so
    // atomicity is the same either way; what differs is what an operator is told when a settled
    // run is settled again. Writing the child rows first makes that surface as a primary-key
    // collision on snapshot_fund_total, which says nothing about the actual problem — that
    // something else settled this run while it was still building the file.
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.snapshot_run
                   SET status = 'COMPLETED',
                       completed_at = :completedAt,
                       duration_millis = :durationMillis,
                       position_count = :positionCount,
                       fund_count = :fundCount,
                       positions_object_key = :positionsObjectKey,
                       positions_sha256 = :positionsSha256,
                       manifest_object_key = :manifestObjectKey,
                       manifest_sha256 = :manifestSha256,
                       previous_manifest_sha256 = :previousManifestSha256
                 WHERE run_id = :runId
                   AND status = 'RUNNING'
                """)
            .param("completedAt", Timestamp.from(run.completedAt().orElseThrow()))
            .param("durationMillis", run.durationMillis().orElseThrow())
            .param("positionCount", run.positionCount())
            .param("fundCount", run.fundCount())
            .param("positionsObjectKey", run.positionsObjectKey().orElseThrow())
            .param("positionsSha256", run.positionsSha256().orElseThrow())
            .param("manifestObjectKey", run.manifestObjectKey().orElseThrow())
            .param("manifestSha256", run.manifestSha256().orElseThrow())
            .param("previousManifestSha256", run.previousManifestSha256().orElse(null))
            .param("runId", run.runId())
            .update();
    if (updated != 1) {
      // The guard is WHERE status = 'RUNNING'. Failing it means something settled this run while
      // it was still building the file, and overwriting that would hide whatever did.
      throw new IllegalStateException(
          "snapshot run " + run.runId() + " was no longer RUNNING when it completed");
    }
    for (SnapshotFundTotal total : fundTotals) {
      insertFundTotal(run.runId(), total);
    }
  }

  @Override
  public void fail(UUID runId, Instant failedAt, long durationMillis, String reason) {
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.snapshot_run
                   SET status = 'FAILED',
                       completed_at = :failedAt,
                       duration_millis = :durationMillis,
                       failure_reason = :reason
                 WHERE run_id = :runId
                   AND status = 'RUNNING'
                """)
            .param("failedAt", Timestamp.from(failedAt))
            .param("durationMillis", durationMillis)
            .param("reason", reason)
            .param("runId", runId)
            .update();
    if (updated != 1) {
      // Not thrown: this runs from a catch block, and replacing the original failure with a second
      // one would lose the reason the pass failed in the first place. Logged loudly instead,
      // because the alternative is a log line saying "snapshot failed" beside a row that says
      // COMPLETED, with nothing anywhere flagging that the two disagree.
      LOGGER.error(
          "snapshot run {} could not be settled FAILED — it was no longer RUNNING. The row and the"
              + " log now disagree about this pass; the row is the record.",
          runId);
    }
  }

  @Override
  public Optional<SnapshotRun> latestCompletedFor(LocalDate businessDate) {
    return jdbc.sql(
            SELECT_RUN
                + """
                 WHERE business_date = :businessDate
                   AND status = 'COMPLETED'
                 ORDER BY completed_at DESC, started_at DESC
                 LIMIT 1
                """)
        .param("businessDate", businessDate)
        .query(JdbcSnapshotRepository::mapRun)
        .optional();
  }

  @Override
  public Optional<SnapshotRun> latestCompletedBefore(LocalDate businessDate) {
    return jdbc.sql(
            SELECT_RUN
                + """
                 WHERE business_date < :businessDate
                   AND status = 'COMPLETED'
                 ORDER BY business_date DESC, completed_at DESC, started_at DESC
                 LIMIT 1
                """)
        .param("businessDate", businessDate)
        .query(JdbcSnapshotRepository::mapRun)
        .optional();
  }

  @Override
  public List<SnapshotFundTotal> fundTotalsOf(UUID runId) {
    return jdbc.sql(
            """
            SELECT fund_id, unit_ledger_id, position_count, units_scaled, currency,
                   value_minor_units, units_in_issue
              FROM workflow.snapshot_fund_total
             WHERE run_id = :runId
             ORDER BY unit_ledger_id
            """)
        .param("runId", runId)
        .query(JdbcSnapshotRepository::mapFundTotal)
        .list();
  }

  @Override
  public List<SnapshotRun> completedRunsAscending() {
    return jdbc.sql(
            SELECT_RUN
                + """
                 WHERE status = 'COMPLETED'
                 ORDER BY business_date ASC
                """)
        .query(JdbcSnapshotRepository::mapRun)
        .list();
  }

  private void insertFundTotal(UUID runId, SnapshotFundTotal total) {
    jdbc.sql(
            """
            INSERT INTO workflow.snapshot_fund_total (
                run_id, fund_id, unit_ledger_id, position_count, units_scaled, currency,
                value_minor_units, units_in_issue)
            VALUES (
                :runId, :fundId, :unitLedgerId, :positionCount, :unitsScaled, :currency,
                :valueMinorUnits, :unitsInIssue)
            """)
        .param("runId", runId)
        .param("fundId", total.fundId().value())
        .param("unitLedgerId", total.unitLedgerId())
        .param("positionCount", total.positionCount())
        .param("unitsScaled", total.units().scaled())
        .param("currency", total.value().currency().name())
        .param("valueMinorUnits", total.value().cents())
        .param("unitsInIssue", total.unitsInIssue().scaled())
        .update();
  }

  private static SnapshotRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    Timestamp completedAt = rs.getTimestamp("completed_at");
    long durationMillis = rs.getLong("duration_millis");
    boolean durationPresent = !rs.wasNull();
    return new SnapshotRun(
        rs.getObject("run_id", UUID.class),
        rs.getObject("business_date", LocalDate.class),
        SnapshotStatus.parse(rs.getString("status")),
        rs.getTimestamp("started_at").toInstant(),
        Optional.ofNullable(completedAt).map(Timestamp::toInstant),
        durationPresent ? Optional.of(durationMillis) : Optional.empty(),
        rs.getLong("position_count"),
        rs.getInt("fund_count"),
        Optional.ofNullable(rs.getString("positions_object_key")),
        Optional.ofNullable(rs.getString("positions_sha256")),
        Optional.ofNullable(rs.getString("manifest_object_key")),
        Optional.ofNullable(rs.getString("manifest_sha256")),
        Optional.ofNullable(rs.getString("previous_manifest_sha256")),
        Optional.ofNullable(rs.getString("failure_reason")));
  }

  private static SnapshotFundTotal mapFundTotal(ResultSet rs, int rowNum) throws SQLException {
    return new SnapshotFundTotal(
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getLong("unit_ledger_id"),
        rs.getLong("position_count"),
        Units.of(rs.getLong("units_scaled")),
        Money.of(rs.getLong("value_minor_units"), CurrencyCode.valueOf(rs.getString("currency"))),
        Units.of(rs.getLong("units_in_issue")));
  }
}
