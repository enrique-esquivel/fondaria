package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.projection.config.HaltProperties;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The shared halt flag: {@code workflow.projection_halt}, read and raised by every replica
 * (ADR-0032 §2, row 26f).
 *
 * <p>The table is PostgreSQL, not Valkey or a broker broadcast, because the worker cannot make
 * progress without PostgreSQL anyway (so the flag adds no availability dependency), and because a
 * pod that starts after a halt must still find it. It is in {@code workflow}, not {@code
 * projection}, because it is operational state with a lifecycle and must survive a projection
 * rebuild (ADR-0011).
 *
 * <p>This class only reads and inserts. A worker never clears a halt: {@code fondaria_cdc} holds no
 * {@code UPDATE} on the table, and an operator clears it in place ({@code
 * docs/RUNBOOKS.md#cdc-replay} §2). Every call carries a statement timeout, so a slow database
 * cannot park a consumer thread.
 *
 * <p>What it records is deliberately thin: a reason code and a queue name, never a decode
 * exception's text (which can quote message content — docs/ENGINEERING-RULES.md §7).
 */
@Component
public class ClusterHaltFlag {

  private static final String RAISE =
      """
      INSERT INTO workflow.projection_halt
        (projection_name, halted_by, reason_code, source_queue)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (projection_name) WHERE cleared_at IS NULL DO NOTHING
      """;

  private static final String ACTIVE =
      """
      SELECT halt_id, halted_by, reason_code, source_queue
      FROM workflow.projection_halt
      WHERE projection_name = ? AND cleared_at IS NULL
      """;

  private final JdbcTemplate jdbc;
  private final String projectionName;
  private final String instanceId;

  public ClusterHaltFlag(
      DataSource dataSource, HaltProperties properties, CheckpointProperties checkpointProperties) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(properties.queryTimeoutSeconds());
    this.projectionName = properties.projectionName();
    this.instanceId = checkpointProperties.instanceId();
  }

  /**
   * Raise the halt, if none is active. Idempotent: a second replica that meets the same poison
   * finds the first one's row and leaves it standing.
   *
   * @return {@code true} if this call inserted the row, {@code false} if a halt was already active
   * @throws org.springframework.dao.DataAccessException if the database could not be reached
   */
  public boolean raise(String reasonCode, String sourceQueue) {
    return jdbc.update(RAISE, projectionName, instanceId, reasonCode, sourceQueue) > 0;
  }

  /**
   * The active (uncleared) halt, if any.
   *
   * @throws org.springframework.dao.DataAccessException if the database could not be reached
   */
  public Optional<ActiveHalt> active() {
    return jdbc
        .query(
            ACTIVE,
            (rs, row) ->
                new ActiveHalt(
                    rs.getLong("halt_id"),
                    rs.getString("halted_by"),
                    rs.getString("reason_code"),
                    rs.getString("source_queue")),
            projectionName)
        .stream()
        .findFirst();
  }

  /** An uncleared halt row. */
  public record ActiveHalt(long haltId, String haltedBy, String reasonCode, String sourceQueue) {}
}
