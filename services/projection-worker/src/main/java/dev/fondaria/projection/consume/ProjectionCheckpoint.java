package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.CheckpointProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How far each projection consumer has committed — {@code
 * projection.projection_consumer_checkpoint}, one row per AMQP consumer (ADR-0032 §1, row 26e).
 *
 * <p><b>One writer per row.</b> A row is keyed by {@link ConsumerRef#id()} ({@code <pod>/<AMQP
 * consumer tag>}). A consumer's deliveries run on one thread at a time, so no two transactions ever
 * contend for the same row's lock. The single shared row this replaces ({@code
 * projection.projection_checkpoint}) made every consumer thread commit one at a time.
 *
 * <p><b>What a row means.</b> {@link #advanceTo} runs inside the sink's transaction, with {@code
 * GREATEST}: a row is the highest ledger timestamp <i>that consumer</i> has committed, and never
 * runs ahead of that consumer's own committed data. Its deliveries are still not timestamp-ordered
 * (competing consumers on one queue interleave; redeliveries happen), which is why it is {@code
 * GREATEST} and not an assignment.
 *
 * <p><b>What the head means.</b> {@link #HEAD} is {@code MIN} over consumers that advanced within
 * the active window, falling back to {@code MAX} over every row when all are idle — an idle shard
 * is caught up, not behind. It is a monitoring estimate, <b>not</b> a gap-free watermark: a
 * competing consumer may hold an older, uncommitted delivery, so "every event at or before the head
 * is committed" does not hold under concurrent consumers and nothing may rely on it. Replay is
 * truncate plus full rebuild ({@code docs/RUNBOOKS.md#cdc-replay}); the checkpoint is never its
 * input. The runbook quotes {@link #HEAD}; nothing in the worker reads it.
 *
 * <p><b>Cleanup.</b> {@link #removeStale} deletes rows idle past the retention (keeping the row
 * that holds the highest timestamp), at startup and every ten minutes, on every replica. It needs
 * no knowledge of which pods are alive: a dead pod's rows, and rows orphaned by a reconnect (new
 * consumer tag), simply age out. A live but idle consumer's row may go too; its next message
 * re-inserts it.
 */
@Component
public class ProjectionCheckpoint {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionCheckpoint.class);

  /** Identifies this projection in {@code projection.projection_consumer_checkpoint}. */
  public static final String PROJECTION_NAME = "projection-worker";

  private static final long CLEANUP_INTERVAL_MILLIS = 600_000L;

  private static final String UPSERT =
      """
      INSERT INTO projection.projection_consumer_checkpoint
        (projection_name, consumer_id, queue_name, last_ledger_timestamp)
      VALUES (:name, :consumerId, :queueName, CAST(:ledgerTimestamp AS numeric))
      ON CONFLICT (projection_name, consumer_id) DO UPDATE
      SET last_ledger_timestamp = GREATEST(
            projection.projection_consumer_checkpoint.last_ledger_timestamp,
            EXCLUDED.last_ledger_timestamp),
          queue_name = EXCLUDED.queue_name,
          updated_at = now()
      RETURNING last_ledger_timestamp
      """;

  /**
   * The worker's head across every replica: {@code MIN} over consumers active within {@code
   * :activeMillis}, else {@code MAX} over every row; {@code NULL} when there are no rows. {@code
   * docs/RUNBOOKS.md#cdc-replay} quotes this query; {@code ProjectionCheckpointIntegrationTest}
   * pins it. The pod-level gauge ({@link CdcLagMetrics}) computes the same thing in memory.
   */
  static final String HEAD =
      """
      SELECT COALESCE(
        (SELECT min(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint
          WHERE projection_name = :name
            AND updated_at > now() - :activeMillis * interval '1 millisecond'),
        (SELECT max(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint
          WHERE projection_name = :name))
      """;

  /**
   * Prunes rows idle past the retention, except the row(s) holding the highest timestamp: those
   * keep {@link #HEAD}'s all-idle fallback meaningful, so a worker stalled for longer than the
   * retention still reads as far behind rather than as "no head at all".
   */
  private static final String REMOVE_STALE =
      """
      DELETE FROM projection.projection_consumer_checkpoint
      WHERE projection_name = :name
        AND updated_at < now() - :retentionMillis * interval '1 millisecond'
        AND last_ledger_timestamp < (
          SELECT max(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint
          WHERE projection_name = :name)
      """;

  private final JdbcClient jdbc;
  private final CdcLagMetrics metrics;
  private final CheckpointProperties properties;
  private final Clock clock;

  public ProjectionCheckpoint(
      JdbcClient jdbc, CdcLagMetrics metrics, CheckpointProperties properties, Clock clock) {
    this.jdbc = jdbc;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Move {@code consumer}'s row to {@code ledgerTimestamp} if it is beyond where the row already
   * stands. Must be called inside the caller's transaction.
   *
   * @param ledgerTimestamp the change event's {@code timestamp} — a u64 nanosecond value as a
   *     canonical base-10 string (as {@code LedgerEventDecoder} produces it)
   */
  public void advanceTo(ConsumerRef consumer, String ledgerTimestamp) {
    // The metric is set from what GREATEST actually stored, not the argument this call passed:
    // this consumer's deliveries are not timestamp-ordered, so "last value passed" could report
    // the row moving backward when it never did.
    String stored =
        jdbc.sql(UPSERT)
            .param("name", PROJECTION_NAME)
            .param("consumerId", consumer.id())
            .param("queueName", consumer.queue())
            .param("ledgerTimestamp", ledgerTimestamp)
            .query(String.class)
            .single();
    metrics.lastTimestampAdvancedTo(consumer.id(), stored);
  }

  /**
   * Delete rows idle past the retention, and forget this process's in-memory marks for consumers
   * idle as long. Idempotent; safe to run on every replica at once.
   *
   * @return the number of rows deleted
   */
  @Scheduled(initialDelay = 0L, fixedDelay = CLEANUP_INTERVAL_MILLIS)
  public int removeStale() {
    Duration retention = properties.retention();
    try {
      int removed =
          jdbc.sql(REMOVE_STALE)
              .param("name", PROJECTION_NAME)
              .param("retentionMillis", retention.toMillis())
              .update();
      metrics.forgetIdleSince(clock.instant().minus(retention));
      if (removed > 0) {
        LOGGER.info("removed {} stale projection checkpoint rows", removed);
      }
      return removed;
    } catch (RuntimeException e) {
      // Housekeeping only: a failed pass leaves stale rows for the next one. The exception's
      // message is not logged -- a driver message can carry a hostname (AGENTS.md §7).
      LOGGER.warn("stale checkpoint cleanup failed: {}", e.getClass().getSimpleName());
      return 0;
    }
  }

  /**
   * One consumer's identity in the checkpoint table.
   *
   * @param id {@code <instance id>/<AMQP consumer tag>} — the row key
   * @param queue the queue this consumer drains (informational)
   */
  public record ConsumerRef(String id, String queue) {

    public ConsumerRef {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(queue, "queue");
      if (id.isBlank()) {
        throw new IllegalArgumentException("a checkpoint consumer id must not be blank");
      }
    }

    /** {@code <instanceId>/<consumerTag>}, the shape ADR-0032 §1 fixes. */
    public static ConsumerRef of(String instanceId, String consumerTag, String queue) {
      // A missing tag would put every consumer on one "<instance>/null" row and bring back the
      // single-row serialization silently. The listener container always stamps it; fail loudly if
      // that ever stops being true.
      if (consumerTag == null || consumerTag.isBlank()) {
        throw new IllegalStateException("delivery carries no AMQP consumer tag");
      }
      return new ConsumerRef(instanceId + "/" + consumerTag, queue);
    }
  }
}
