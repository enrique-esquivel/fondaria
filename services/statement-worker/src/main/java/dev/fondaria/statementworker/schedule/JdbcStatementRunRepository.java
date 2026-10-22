package dev.fondaria.statementworker.schedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StatementRunRepository} over {@code workflow.statement_run} ({@code V30}), using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
public class JdbcStatementRunRepository implements StatementRunRepository {

  private static final String SELECT_COLUMNS =
      """
      SELECT period, close_business_date, status, started_at, completed_at, duration_millis,
             expected_count, completed_count, failure_reason
        FROM workflow.statement_run
      """;

  private final JdbcClient jdbc;

  public JdbcStatementRunRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void openIfAbsent(
      String period, LocalDate closeBusinessDate, Instant startedAt, long expectedCount) {
    jdbc.sql(
            """
            INSERT INTO workflow.statement_run (
                period, close_business_date, status, started_at, expected_count)
            VALUES (:period, :closeBusinessDate, 'RUNNING', :startedAt, :expectedCount)
            ON CONFLICT (period) DO NOTHING
            """)
        .param("period", period)
        .param("closeBusinessDate", closeBusinessDate)
        .param("startedAt", Timestamp.from(startedAt))
        .param("expectedCount", expectedCount)
        .update();
  }

  @Override
  public List<StatementRun> findRunning() {
    return jdbc.sql(SELECT_COLUMNS + " WHERE status = 'RUNNING' ORDER BY period")
        .query(JdbcStatementRunRepository::map)
        .list();
  }

  @Override
  public void complete(
      String period, Instant completedAt, long durationMillis, long completedCount) {
    jdbc.sql(
            """
            UPDATE workflow.statement_run
               SET status = 'COMPLETED',
                   completed_at = :completedAt,
                   duration_millis = :durationMillis,
                   completed_count = :completedCount
             WHERE period = :period
               AND status = 'RUNNING'
            """)
        .param("completedAt", Timestamp.from(completedAt))
        .param("durationMillis", durationMillis)
        .param("completedCount", completedCount)
        .param("period", period)
        .update();
  }

  @Override
  public Optional<StatementRun> find(String period) {
    return jdbc.sql(SELECT_COLUMNS + " WHERE period = :period")
        .param("period", period)
        .query(JdbcStatementRunRepository::map)
        .optional();
  }

  private static StatementRun map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp completedAt = rs.getTimestamp("completed_at");
    long durationMillis = rs.getLong("duration_millis");
    boolean durationPresent = !rs.wasNull();
    long completedCount = rs.getLong("completed_count");
    boolean completedCountPresent = !rs.wasNull();
    return new StatementRun(
        rs.getString("period"),
        rs.getObject("close_business_date", LocalDate.class),
        StatementRunStatus.parse(rs.getString("status")),
        rs.getTimestamp("started_at").toInstant(),
        Optional.ofNullable(completedAt).map(Timestamp::toInstant),
        durationPresent ? Optional.of(durationMillis) : Optional.empty(),
        rs.getLong("expected_count"),
        completedCountPresent ? Optional.of(completedCount) : Optional.empty(),
        Optional.ofNullable(rs.getString("failure_reason")));
  }
}
