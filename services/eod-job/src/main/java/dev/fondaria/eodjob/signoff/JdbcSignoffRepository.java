package dev.fondaria.eodjob.signoff;

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
 * {@link SignoffRepository} over {@code workflow.close_signoff} ({@code V29}), using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>No transaction wraps a method here. {@link #insertIfAbsent} is one statement; {@link
 * #markAnnounced} is one statement; and the two-step "write then announce" of {@link
 * SignoffService} is deliberately <b>not</b> one transaction — the row must be durably committed
 * before the event is published, so a crash after the publish still leaves the row, and a crash
 * before {@code markAnnounced} leaves a row a later sweep re-announces.
 */
@Repository
public class JdbcSignoffRepository implements SignoffRepository {

  private final JdbcClient jdbc;

  public JdbcSignoffRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(CloseSignoff signoff) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO workflow.close_signoff (
                    business_date, signed_off_at, reconciliation_run_id, custodian_run_id,
                    snapshot_run_id, snapshot_position_count, snapshot_object_key,
                    snapshot_manifest_key)
                VALUES (
                    :businessDate, :signedOffAt, :reconciliationRunId, :custodianRunId,
                    :snapshotRunId, :snapshotPositionCount, :snapshotObjectKey,
                    :snapshotManifestKey)
                ON CONFLICT (business_date) DO NOTHING
                """)
            .param("businessDate", signoff.businessDate())
            .param("signedOffAt", Timestamp.from(signoff.signedOffAt()))
            .param("reconciliationRunId", signoff.reconciliationRunId())
            .param("custodianRunId", signoff.custodianRunId())
            .param("snapshotRunId", signoff.snapshotRunId())
            .param("snapshotPositionCount", signoff.snapshotPositionCount())
            .param("snapshotObjectKey", signoff.snapshotObjectKey())
            .param("snapshotManifestKey", signoff.snapshotManifestKey())
            .update();
    return inserted == 1;
  }

  private static final String SELECT_COLUMNS =
      """
      SELECT business_date, signed_off_at, reconciliation_run_id, custodian_run_id,
             snapshot_run_id, snapshot_position_count, snapshot_object_key,
             snapshot_manifest_key, announced_at
        FROM workflow.close_signoff
      """;

  @Override
  public void markAnnounced(LocalDate businessDate, Instant announcedAt) {
    jdbc.sql(
            """
            UPDATE workflow.close_signoff
               SET announced_at = GREATEST(:announcedAt, signed_off_at)
             WHERE business_date = :businessDate
               AND announced_at IS NULL
            """)
        .param("announcedAt", Timestamp.from(announcedAt))
        .param("businessDate", businessDate)
        .update();
  }

  @Override
  public java.util.List<CloseSignoff> findUnannounced() {
    return jdbc.sql(SELECT_COLUMNS + " WHERE announced_at IS NULL ORDER BY business_date")
        .query(JdbcSignoffRepository::map)
        .list();
  }

  @Override
  public Optional<CloseSignoff> find(LocalDate businessDate) {
    return jdbc.sql(SELECT_COLUMNS + " WHERE business_date = :businessDate")
        .param("businessDate", businessDate)
        .query(JdbcSignoffRepository::map)
        .optional();
  }

  @Override
  public Optional<LocalDate> latestSignedOffDate() {
    return jdbc.sql("SELECT max(business_date) FROM workflow.close_signoff")
        .query((rs, rowNum) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
        .single();
  }

  private static CloseSignoff map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp announcedAt = rs.getTimestamp("announced_at");
    return new CloseSignoff(
        rs.getObject("business_date", LocalDate.class),
        rs.getTimestamp("signed_off_at").toInstant(),
        rs.getObject("reconciliation_run_id", UUID.class),
        rs.getObject("custodian_run_id", UUID.class),
        rs.getObject("snapshot_run_id", UUID.class),
        rs.getLong("snapshot_position_count"),
        rs.getString("snapshot_object_key"),
        rs.getString("snapshot_manifest_key"),
        Optional.ofNullable(announcedAt).map(Timestamp::toInstant));
  }
}
