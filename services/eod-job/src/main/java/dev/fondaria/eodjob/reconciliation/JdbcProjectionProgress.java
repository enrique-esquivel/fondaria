package dev.fondaria.eodjob.reconciliation;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ProjectionProgress} over {@code projection.projection_consumer_checkpoint}: one aggregate
 * over a table of one row per consumer (tens of rows), so it costs a millisecond however much
 * history the projection holds. {@code updated_at} is compared with the database's own {@code
 * now()}, the clock that wrote it.
 */
@Repository
public class JdbcProjectionProgress implements ProjectionProgress {

  /** The projection-worker's name in the checkpoint table ({@code ProjectionCheckpoint}). */
  static final String PROJECTION_NAME = "projection-worker";

  private static final String SNAPSHOT =
      """
      SELECT max(last_ledger_timestamp)::bigint AS highest,
             count(*) FILTER (
               WHERE last_ledger_timestamp < :ledgerHead
                 AND updated_at > now() - :quietMillis * interval '1 millisecond') AS busy_behind,
             (extract(epoch FROM now() - max(updated_at)) * 1000)::bigint AS idle_millis
        FROM projection.projection_consumer_checkpoint
       WHERE projection_name = :name
      """;

  private final JdbcClient jdbc;
  private final String projectionName;

  @Autowired
  public JdbcProjectionProgress(JdbcClient jdbc) {
    this(jdbc, PROJECTION_NAME);
  }

  /** For a test that must read only the checkpoint rows it wrote itself. */
  JdbcProjectionProgress(JdbcClient jdbc, String projectionName) {
    this.jdbc = jdbc;
    this.projectionName = projectionName;
  }

  @Override
  public Snapshot against(long ledgerHeadNanos, Duration quietPeriod) {
    return jdbc.sql(SNAPSHOT)
        .param("name", projectionName)
        .param("ledgerHead", ledgerHeadNanos)
        .param("quietMillis", quietPeriod.toMillis())
        .query(
            (rs, row) -> {
              long highest = rs.getLong("highest");
              boolean none = rs.wasNull();
              long busyBehind = rs.getLong("busy_behind");
              long idleMillis = rs.getLong("idle_millis");
              return new Snapshot(
                  none ? Optional.empty() : Optional.of(highest),
                  busyBehind,
                  none
                      ? Optional.empty()
                      : Optional.of(Duration.ofMillis(Math.max(0L, idleMillis))));
            })
        .single();
  }
}
