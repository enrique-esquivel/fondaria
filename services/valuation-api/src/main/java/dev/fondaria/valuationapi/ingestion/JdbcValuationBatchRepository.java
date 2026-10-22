package dev.fondaria.valuationapi.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ValuationBatchRepository} over {@code workflow.valuation_batch}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
public class JdbcValuationBatchRepository implements ValuationBatchRepository {

  private static final String SELECT_BATCH =
      """
      SELECT batch_id, source, object_key, checksum_sha256, status,
             declared_row_count, accepted_valuation_count,
             rejection_code, rejection_message, rejection_details,
             validation_started_at, submitted_at, status_updated_at
        FROM workflow.valuation_batch
      """;

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public JdbcValuationBatchRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * <b>{@code ON CONFLICT DO NOTHING} carries no inference specification, and must not.</b> Naming
   * one index suppresses conflicts on that index alone and PostgreSQL still raises on any other
   * unique constraint — the mistake {@code JdbcCashReceiptRepository} turned into a 500 once
   * already. Here two constraints mean the same thing: the primary key on the derived {@code
   * batch_id} and the content-address unique key it is derived from. A conflict on either is the
   * fact "this file has already been submitted".
   *
   * <p>The insert and the read-back are one transaction so a caller always sees a batch, whether or
   * not this call created it.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ValuationBatch submit(ValuationBatch batch) {
    if (batch.status() != ValuationBatchStatus.RECEIVED) {
      throw new IllegalArgumentException("A batch is submitted as RECEIVED and validated later");
    }
    jdbc.sql(
            """
            INSERT INTO workflow.valuation_batch (
                batch_id, source, object_key, checksum_sha256, status,
                declared_row_count, submitted_at, status_updated_at)
            VALUES (:batchId, :source, :objectKey, :checksumSha256, :status,
                    :declaredRowCount, :submittedAt, :statusUpdatedAt)
            ON CONFLICT DO NOTHING
            """)
        .param("batchId", batch.batchId())
        .param("source", batch.source())
        .param("objectKey", batch.objectKey())
        .param("checksumSha256", batch.checksumSha256())
        .param("status", batch.status().name())
        .param("declaredRowCount", batch.declaredRowCount())
        .param("submittedAt", Timestamp.from(batch.submittedAt()))
        .param("statusUpdatedAt", Timestamp.from(batch.statusUpdatedAt()))
        .update();

    // Read back by content address rather than by batch_id. They resolve to the same row —
    // the id is derived from exactly those three values — but reading by the address is
    // what proves it, and would surface a derivation that had drifted rather than returning
    // nothing and looking like a lost insert.
    return jdbc.sql(
            SELECT_BATCH
                + """
                 WHERE source = :source
                   AND object_key = :objectKey
                   AND checksum_sha256 = :checksumSha256
                """)
        .param("source", batch.source())
        .param("objectKey", batch.objectKey())
        .param("checksumSha256", batch.checksumSha256())
        .query(this::mapBatch)
        .optional()
        .orElseThrow(
            () -> new IllegalStateException("Valuation batch vanished immediately after insert"));
  }

  @Override
  public Optional<ValuationBatch> find(UUID batchId) {
    return jdbc.sql(SELECT_BATCH + " WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .query(this::mapBatch)
        .optional();
  }

  /**
   * {@code SKIP LOCKED}, not {@code NOWAIT}. Two instances sweeping at once should each take work
   * nobody else holds and get on with it. The {@code UPDATE ... RETURNING} makes claiming and
   * marking one statement, so no batch can be claimed twice by a race between a select and an
   * update.
   */
  @Override
  @Transactional
  public List<ValuationBatch> claimForValidation(Instant now, Instant staleBefore, int limit) {
    return jdbc.sql(
            """
            UPDATE workflow.valuation_batch
               SET status = 'VALIDATING',
                   validation_started_at = :now,
                   status_updated_at = :now
             WHERE batch_id IN (
                   SELECT batch_id
                     FROM workflow.valuation_batch
                    WHERE status = 'RECEIVED'
                       OR (status = 'VALIDATING' AND validation_started_at < :staleBefore)
                    ORDER BY submitted_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            RETURNING batch_id, source, object_key, checksum_sha256, status,
                      declared_row_count, accepted_valuation_count,
                      rejection_code, rejection_message, rejection_details,
                      validation_started_at, submitted_at, status_updated_at
            """)
        .param("now", Timestamp.from(now))
        .param("staleBefore", Timestamp.from(staleBefore))
        .param("limit", limit)
        .query(this::mapBatch)
        .list();
  }

  /**
   * Guarded by {@code status = 'VALIDATING'}. A pass whose lease went stale, and whose batch was
   * therefore reclaimed and finished by someone else, must not write its own late answer over the
   * one already there.
   */
  @Override
  public boolean resolve(ValuationBatch batch) {
    if (batch.status() == ValuationBatchStatus.VALIDATING) {
      throw new IllegalArgumentException("resolve moves a batch out of VALIDATING");
    }
    return jdbc.sql(
                """
            UPDATE workflow.valuation_batch
               SET status = :status,
                   accepted_valuation_count = :acceptedValuationCount,
                   rejection_code = :rejectionCode,
                   rejection_message = :rejectionMessage,
                   rejection_details = CAST(:rejectionDetails AS jsonb),
                   validation_started_at = NULL,
                   status_updated_at = :statusUpdatedAt
             WHERE batch_id = :batchId
               AND status = 'VALIDATING'
            """)
            .param("batchId", batch.batchId())
            .param("status", batch.status().name())
            .param(
                "acceptedValuationCount",
                batch.acceptedValuationCount().isPresent()
                    ? batch.acceptedValuationCount().getAsInt()
                    : null,
                Types.INTEGER)
            .param(
                "rejectionCode",
                batch.rejection().map(r -> r.code().name()).orElse(null),
                Types.VARCHAR)
            .param(
                "rejectionMessage",
                batch.rejection().map(ValuationRejection::message).orElse(null),
                Types.VARCHAR)
            .param(
                "rejectionDetails",
                batch.rejection().map(r -> writeDetails(r.details())).orElse(null),
                Types.VARCHAR)
            .param("statusUpdatedAt", Timestamp.from(batch.statusUpdatedAt()))
            .update()
        > 0;
  }

  private String writeDetails(Map<String, Object> details) {
    if (details.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      // The details are this codebase's own integers and strings, so this cannot happen;
      // losing the batch's outcome because of it would be far worse than losing the
      // detail, so the outcome is written without it.
      return null;
    }
  }

  private Map<String, Object> readDetails(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private ValuationBatch mapBatch(ResultSet rs, int rowNum) throws SQLException {
    String rejectionCode = rs.getString("rejection_code");
    Optional<ValuationRejection> rejection =
        rejectionCode == null
            ? Optional.empty()
            : Optional.of(
                ValuationRejection.of(
                    ValuationRejectionCode.valueOf(rejectionCode),
                    rs.getString("rejection_message"),
                    readDetails(rs.getString("rejection_details"))));

    int acceptedCount = rs.getInt("accepted_valuation_count");
    OptionalInt accepted = rs.wasNull() ? OptionalInt.empty() : OptionalInt.of(acceptedCount);

    Timestamp leaseStartedAt = rs.getTimestamp("validation_started_at");
    return new ValuationBatch(
        rs.getObject("batch_id", UUID.class),
        rs.getString("source"),
        rs.getString("object_key"),
        rs.getString("checksum_sha256"),
        ValuationBatchStatus.parse(rs.getString("status")),
        rs.getInt("declared_row_count"),
        accepted,
        rejection,
        Optional.ofNullable(leaseStartedAt).map(Timestamp::toInstant),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getTimestamp("status_updated_at").toInstant());
  }
}
