package dev.fondaria.contribution.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ContributionBatchRepository} over {@code workflow.contribution_batch}, using {@code
 * JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p><b>No amount is ever summed here, and no balance is ever read here.</b> {@code
 * declared_total_minor_units} is selected as a single {@code long} for a single batch, because it
 * is that batch's own declaration. A {@code SUM} over this column would be a second, wrong answer
 * to a question the ledger already answers correctly.
 */
@Repository
public class JdbcContributionBatchRepository implements ContributionBatchRepository {

  private static final String SELECT_BATCH =
      """
      SELECT batch_id, organization_id, object_key, checksum_sha256, status,
             plan_id, payroll_period, value_date,
             declared_total_minor_units, declared_row_count,
             declared_employee_minor_units, declared_employer_minor_units,
             declared_voluntary_minor_units, file_validated_at,
             rejection_code, rejection_message, rejection_details,
             validation_started_at, staged_at, submitted_at, status_updated_at
        FROM workflow.contribution_batch
      """;

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public JdbcContributionBatchRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * <b>{@code ON CONFLICT DO NOTHING} carries no inference specification, and must not.</b> Naming
   * one index suppresses conflicts on that index alone and PostgreSQL still raises on any other
   * unique constraint — which turned a concurrent duplicate into a 500 once already, in {@code
   * JdbcCashReceiptRepository}. Here there are two constraints that mean the same thing: the
   * primary key on the derived {@code batch_id} and the content-address unique key it is derived
   * from. A conflict on either is the fact "this file has already been submitted".
   *
   * <p>The insert and the read-back are one transaction so a caller always sees a batch, whether or
   * not this call created it.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ContributionBatch submit(ContributionBatch batch) {
    if (batch.status() != ContributionBatchStatus.RECEIVED) {
      throw new IllegalArgumentException("A batch is submitted as RECEIVED and validated later");
    }
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch (
                batch_id, organization_id, object_key, checksum_sha256, status,
                plan_id, payroll_period, value_date,
                declared_total_minor_units, declared_row_count,
                declared_employee_minor_units, declared_employer_minor_units,
                declared_voluntary_minor_units,
                submitted_at, status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksumSha256, :status,
                    :planId, :payrollPeriod, :valueDate,
                    :declaredTotalMinorUnits, :declaredRowCount,
                    :declaredEmployeeMinorUnits, :declaredEmployerMinorUnits,
                    :declaredVoluntaryMinorUnits,
                    :submittedAt, :statusUpdatedAt)
            ON CONFLICT DO NOTHING
            """)
        .param("batchId", batch.batchId())
        .param("organizationId", batch.organizationId().value())
        .param("objectKey", batch.objectKey())
        .param("checksumSha256", batch.checksumSha256())
        .param("status", batch.status().name())
        .param("planId", batch.declared().planId())
        .param("payrollPeriod", batch.declared().payrollPeriod())
        .param("valueDate", batch.declared().valueDate())
        .param("declaredTotalMinorUnits", batch.declared().declaredTotalCents())
        .param("declaredRowCount", batch.declared().declaredRowCount())
        .param(
            "declaredEmployeeMinorUnits",
            batch.declared().declaredCentsFor(ContributionSource.EMPLOYEE))
        .param(
            "declaredEmployerMinorUnits",
            batch.declared().declaredCentsFor(ContributionSource.EMPLOYER))
        .param(
            "declaredVoluntaryMinorUnits",
            batch.declared().declaredCentsFor(ContributionSource.VOLUNTARY))
        .param("submittedAt", Timestamp.from(batch.submittedAt()))
        .param("statusUpdatedAt", Timestamp.from(batch.statusUpdatedAt()))
        .update();

    // Read back by content address rather than by batch_id. They resolve to the same row —
    // the id is derived from exactly those three values — but reading by the address is what
    // proves it, and would surface a derivation that had drifted rather than returning
    // nothing and looking like a lost insert.
    //
    // Scoped to files, exactly as the content-address unique index is (V24). Every part of a
    // split file carries its file's object key and checksum, because it IS that file — so
    // without this predicate, resubmitting a file that had already been divided would match
    // the file and all of its parts and fail on the row count. That is not hypothetical: it
    // is the ordinary case for any employer who resubmits a large file.
    return jdbc.sql(
            SELECT_BATCH
                + """
                 WHERE organization_id = :organizationId
                   AND object_key = :objectKey
                   AND checksum_sha256 = :checksumSha256
                   AND parent_batch_id IS NULL
                """)
        .param("organizationId", batch.organizationId().value())
        .param("objectKey", batch.objectKey())
        .param("checksumSha256", batch.checksumSha256())
        .query(this::mapBatch)
        .optional()
        .orElseThrow(
            () ->
                new IllegalStateException("Contribution batch vanished immediately after insert"));
  }

  /**
   * {@code ON CONFLICT DO NOTHING} without an inference specification, for the same reason {@link
   * #submit} carries none: a part collides on its derived primary key and on {@code
   * (parent_batch_id, part_number)} alike, and either conflict means the same thing — this part of
   * this file has already been recorded.
   */
  @Override
  public boolean insertPartIfAbsent(ContributionBatch part, UUID fileBatchId, int partNumber) {
    if (part.status() != ContributionBatchStatus.PENDING_RECONCILIATION) {
      throw new IllegalArgumentException(
          "A part is recorded PENDING_RECONCILIATION and staged with its file, not before");
    }
    if (partNumber < 2) {
      throw new IllegalArgumentException(
          "The submitted batch is part 1 and is not inserted as a part; partNumber=" + partNumber);
    }
    return jdbc.sql(
                """
            INSERT INTO workflow.contribution_batch (
                batch_id, organization_id, object_key, checksum_sha256, status,
                parent_batch_id, part_number,
                plan_id, payroll_period, value_date,
                declared_total_minor_units, declared_row_count,
                declared_employee_minor_units, declared_employer_minor_units,
                declared_voluntary_minor_units,
                file_validated_at, submitted_at, status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksumSha256, :status,
                    :parentBatchId, :partNumber,
                    :planId, :payrollPeriod, :valueDate,
                    :declaredTotalMinorUnits, :declaredRowCount,
                    :declaredEmployeeMinorUnits, :declaredEmployerMinorUnits,
                    :declaredVoluntaryMinorUnits,
                    :fileValidatedAt, :submittedAt, :statusUpdatedAt)
            ON CONFLICT DO NOTHING
            """)
            .param("batchId", part.batchId())
            .param("organizationId", part.organizationId().value())
            .param("objectKey", part.objectKey())
            .param("checksumSha256", part.checksumSha256())
            .param("status", part.status().name())
            .param("parentBatchId", fileBatchId)
            .param("partNumber", partNumber)
            .param("planId", part.declared().planId())
            .param("payrollPeriod", part.declared().payrollPeriod())
            .param("valueDate", part.declared().valueDate())
            .param("declaredTotalMinorUnits", part.declared().declaredTotalCents())
            .param("declaredRowCount", part.declared().declaredRowCount())
            .param(
                "declaredEmployeeMinorUnits",
                part.declared().declaredCentsFor(ContributionSource.EMPLOYEE))
            .param(
                "declaredEmployerMinorUnits",
                part.declared().declaredCentsFor(ContributionSource.EMPLOYER))
            .param(
                "declaredVoluntaryMinorUnits",
                part.declared().declaredCentsFor(ContributionSource.VOLUNTARY))
            .param(
                "fileValidatedAt",
                part.fileValidatedAt().map(Timestamp::from).orElse(null),
                Types.TIMESTAMP)
            .param("submittedAt", Timestamp.from(part.submittedAt()))
            .param("statusUpdatedAt", Timestamp.from(part.statusUpdatedAt()))
            .update()
        > 0;
  }

  /**
   * Guarded on {@code PENDING_RECONCILIATION}, so a part already carried further — allotted between
   * two re-resolves of its file — is never dragged back to {@code STAGED}. {@code staged_at} is
   * write-once here exactly as it is in {@link #resolve}: it is what the cut-off test (Task 21.1)
   * reads, and a part re-dated by an unrelated re-sweep would deal at a NAV struck days after its
   * money was known to be there.
   */
  @Override
  public int stageParts(UUID fileBatchId, Instant stagedAt, Instant statusUpdatedAt) {
    return jdbc.sql(
            """
            UPDATE workflow.contribution_batch
               SET status = 'STAGED',
                   staged_at = COALESCE(staged_at, :stagedAt),
                   status_updated_at = :statusUpdatedAt
             WHERE parent_batch_id = :fileBatchId
               AND status = 'PENDING_RECONCILIATION'
            """)
        .param("fileBatchId", fileBatchId)
        .param("stagedAt", Timestamp.from(stagedAt))
        .param("statusUpdatedAt", Timestamp.from(statusUpdatedAt))
        .update();
  }

  /**
   * One query for both shapes of the question. {@code COALESCE(parent_batch_id, batch_id)} is the
   * file this batch belongs to whether it is the file itself or one of its parts, and the join
   * gathers the family from that one value — so a part and its file give the same counts, read
   * through either id.
   */
  @Override
  public Optional<BatchPartProgress> progressOf(OrganizationId organizationId, UUID batchId) {
    return jdbc.sql(
            """
            WITH asked AS (
                SELECT COALESCE(parent_batch_id, batch_id) AS file_batch_id,
                       COALESCE(part_number, 1)            AS part_number
                  FROM workflow.contribution_batch
                 WHERE organization_id = :organizationId
                   AND batch_id = :batchId)
            SELECT asked.file_batch_id,
                   asked.part_number,
                   COUNT(*)                                         AS part_count,
                   COUNT(*) FILTER (WHERE family.status = 'ALLOTTED') AS allotted_parts
              FROM asked
              JOIN workflow.contribution_batch family
                ON family.organization_id = :organizationId
               AND (family.batch_id = asked.file_batch_id
                    OR family.parent_batch_id = asked.file_batch_id)
             GROUP BY asked.file_batch_id, asked.part_number
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .query(
            (rs, rowNum) ->
                new BatchPartProgress(
                    rs.getObject("file_batch_id", UUID.class),
                    rs.getInt("part_number"),
                    rs.getInt("part_count"),
                    rs.getInt("allotted_parts")))
        .optional();
  }

  @Override
  public Optional<ContributionBatch> find(OrganizationId organizationId, UUID batchId) {
    return jdbc.sql(
            SELECT_BATCH + " WHERE organization_id = :organizationId AND batch_id = :batchId")
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .query(this::mapBatch)
        .optional();
  }

  /**
   * {@code SKIP LOCKED}, not {@code NOWAIT}. Two instances sweeping at once should each take work
   * nobody else holds and get on with it; blocking or failing would make a second instance useless
   * rather than helpful. The {@code UPDATE ... RETURNING} makes claiming and marking one statement,
   * so no batch can be claimed twice by a race between a select and an update.
   */
  @Override
  @Transactional
  public List<ContributionBatch> claimForValidation(Instant now, Instant staleBefore, int limit) {
    return jdbc.sql(
            """
            UPDATE workflow.contribution_batch
               SET status = 'VALIDATING',
                   validation_started_at = :now,
                   status_updated_at = :now
             WHERE batch_id IN (
                   SELECT batch_id
                     FROM workflow.contribution_batch
                    -- Files only (ADR-0017). A part inherits its file's structural
                    -- validation and its file's cash decision, so a sweep has nothing
                    -- to do with one -- and fifty parts could otherwise fill this
                    -- LIMIT and starve the files behind them.
                    WHERE parent_batch_id IS NULL
                      AND (status IN ('RECEIVED', 'PENDING_RECONCILIATION')
                           OR (status = 'VALIDATING' AND validation_started_at < :staleBefore))
                      -- Never reclaim a batch whose child parts have already been staged or
                      -- linked-chained into the ledger (ADR-0017, Task 21.6).
                      AND NOT EXISTS (
                          SELECT 1
                            FROM workflow.contribution_batch child
                           WHERE child.parent_batch_id = workflow.contribution_batch.batch_id
                             AND child.status IN ('STAGED', 'ALLOTTED'))
                    ORDER BY submitted_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            RETURNING batch_id, organization_id, object_key, checksum_sha256, status,
                      plan_id, payroll_period, value_date,
                      declared_total_minor_units, declared_row_count,
                      declared_employee_minor_units, declared_employer_minor_units,
                      declared_voluntary_minor_units, file_validated_at,
                      rejection_code, rejection_message, rejection_details,
                      validation_started_at, staged_at, submitted_at, status_updated_at
            """)
        .param("now", Timestamp.from(now))
        .param("staleBefore", Timestamp.from(staleBefore))
        .param("limit", limit)
        .query(this::mapBatch)
        .list();
  }

  @Override
  public boolean markFileValidated(UUID batchId, Instant fileValidatedAt) {
    return jdbc.sql(
                """
            UPDATE workflow.contribution_batch
               SET file_validated_at = :fileValidatedAt,
                   status_updated_at = :fileValidatedAt
             WHERE batch_id = :batchId
               AND status = 'VALIDATING'
            """)
            .param("batchId", batchId)
            .param("fileValidatedAt", Timestamp.from(fileValidatedAt))
            .update()
        > 0;
  }

  @Override
  public boolean releaseValidation(UUID batchId, Instant now) {
    return jdbc.sql(
                """
            UPDATE workflow.contribution_batch
               SET status = CASE
                              WHEN file_validated_at IS NOT NULL THEN 'PENDING_RECONCILIATION'
                              ELSE 'RECEIVED'
                            END,
                   validation_started_at = NULL,
                   status_updated_at = :now
             WHERE batch_id = :batchId
               AND status = 'VALIDATING'
            """)
            .param("batchId", batchId)
            .param("now", Timestamp.from(now))
            .update()
        > 0;
  }

  /**
   * Guarded by {@code status = 'VALIDATING'}. A pass whose lease went stale, and whose batch was
   * therefore reclaimed and finished by someone else, must not write its own late answer over the
   * one that is already there — the second answer would be computed from the same file and reach
   * the same conclusion, but a rejection silently replaced by a rejection is still a lifecycle this
   * service cannot explain afterwards.
   */
  @Override
  public boolean resolve(ContributionBatch batch) {
    if (batch.status() == ContributionBatchStatus.VALIDATING) {
      throw new IllegalArgumentException("resolve moves a batch out of VALIDATING");
    }
    // The declarations are not written here. They arrived with the submission and are
    // already on the row (contribution-file-v2.md §1.1); an UPDATE that restated them
    // would be a second chance to disagree with what the employer actually submitted.
    return jdbc.sql(
                """
            UPDATE workflow.contribution_batch
               SET status = :status,
                   file_validated_at = COALESCE(:fileValidatedAt, file_validated_at),
                   rejection_code = :rejectionCode,
                   rejection_message = :rejectionMessage,
                   rejection_details = CAST(:rejectionDetails AS jsonb),
                   validation_started_at = NULL,
                   -- Write-once (Task 21.1). A batch that reaches STAGED a second time --
                   -- a stale-lease re-sweep, a re-resolve -- keeps the instant it first
                   -- became dealable, so its cut-off, and therefore its dealing date,
                   -- cannot move underneath it.
                   staged_at = COALESCE(staged_at, :stagedAt),
                   status_updated_at = :statusUpdatedAt
             WHERE batch_id = :batchId
               AND status = 'VALIDATING'
            """)
            .param("batchId", batch.batchId())
            .param("status", batch.status().name())
            .param(
                "fileValidatedAt",
                batch.fileValidatedAt().map(Timestamp::from).orElse(null),
                Types.TIMESTAMP)
            .param(
                "rejectionCode",
                batch.rejection().map(r -> r.code().name()).orElse(null),
                Types.VARCHAR)
            .param(
                "rejectionMessage",
                batch.rejection().map(BatchRejection::message).orElse(null),
                Types.VARCHAR)
            .param(
                "rejectionDetails",
                batch.rejection().map(r -> writeDetails(r.details())).orElse(null),
                Types.VARCHAR)
            .param("stagedAt", batch.stagedAt().map(Timestamp::from).orElse(null), Types.TIMESTAMP)
            .param("statusUpdatedAt", Timestamp.from(batch.statusUpdatedAt()))
            .update()
        > 0;
  }

  @Override
  public List<ContributionBatch> pendingReconciliation(OrganizationId organizationId) {
    return jdbc.sql(
            SELECT_BATCH
                + """
                 WHERE organization_id = :organizationId
                   AND status = 'PENDING_RECONCILIATION'
                   -- Files only (ADR-0017): a part waits on its file's wire, never on
                   -- one of its own, so listing parts would report a single
                   -- outstanding remittance once per part.
                   AND parent_batch_id IS NULL
                 ORDER BY submitted_at
                """)
        .param("organizationId", organizationId.value())
        .query(this::mapBatch)
        .list();
  }

  private String writeDetails(Map<String, Object> details) {
    if (details.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      // The details are this codebase's own integers and enum names, so this cannot
      // happen; losing the batch's outcome because of it would be far worse than losing
      // the detail, so the outcome is written without it.
      return null;
    }
  }

  private Map<String, Object> readDetails(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private ContributionBatch mapBatch(ResultSet rs, int rowNum) throws SQLException {
    BatchDeclaration declared =
        new BatchDeclaration(
            rs.getString("plan_id"),
            rs.getString("payroll_period"),
            rs.getObject("value_date", LocalDate.class),
            rs.getLong("declared_total_minor_units"),
            rs.getInt("declared_row_count"),
            Map.of(
                ContributionSource.EMPLOYEE,
                rs.getLong("declared_employee_minor_units"),
                ContributionSource.EMPLOYER,
                rs.getLong("declared_employer_minor_units"),
                ContributionSource.VOLUNTARY,
                rs.getLong("declared_voluntary_minor_units")));

    String rejectionCode = rs.getString("rejection_code");
    Optional<BatchRejection> rejection =
        rejectionCode == null
            ? Optional.empty()
            : Optional.of(
                BatchRejection.of(
                    BatchRejectionCode.valueOf(rejectionCode),
                    rs.getString("rejection_message"),
                    readDetails(rs.getString("rejection_details"))));

    Timestamp fileValidatedAt = rs.getTimestamp("file_validated_at");
    Timestamp leaseStartedAt = rs.getTimestamp("validation_started_at");
    Timestamp stagedAt = rs.getTimestamp("staged_at");
    return new ContributionBatch(
        rs.getObject("batch_id", UUID.class),
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("object_key"),
        rs.getString("checksum_sha256"),
        ContributionBatchStatus.parse(rs.getString("status")),
        declared,
        rejection,
        Optional.ofNullable(fileValidatedAt).map(Timestamp::toInstant),
        Optional.ofNullable(leaseStartedAt).map(Timestamp::toInstant),
        Optional.ofNullable(stagedAt).map(Timestamp::toInstant),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getTimestamp("status_updated_at").toInstant());
  }
}
