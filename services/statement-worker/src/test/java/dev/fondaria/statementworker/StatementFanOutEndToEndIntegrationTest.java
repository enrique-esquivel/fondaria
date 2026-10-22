package dev.fondaria.statementworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.statementworker.render.RedisCompletionCounter;
import dev.fondaria.statementworker.schedule.StatementRun;
import dev.fondaria.statementworker.schedule.StatementRunRepository;
import dev.fondaria.statementworker.schedule.StatementRunStatus;
import dev.fondaria.statementworker.support.ServiceIntegrationTest;
import dev.fondaria.testing.ObjectStoreFixture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The whole of Task 7.5b, end to end against real infrastructure: a signed-off month-end close fans
 * out to two participants — one whose fund gained, one whose fund lost — each statement balances
 * exactly, and a redelivery of the same close event does not double-count or change a single
 * rendered byte.
 *
 * <p>Asserts only over the period and participants this test creates itself (AGENTS.md §4): the
 * database, the topics and the buckets are shared and reused.
 */
@SpringBootTest
class StatementFanOutEndToEndIntegrationTest extends ServiceIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private JdbcClient jdbc;
  @Autowired private StatementRunRepository runs;
  @Autowired private RedisCompletionCounter completionCounter;
  @Autowired private RabbitTemplate rabbitTemplate;

  @Test
  @DisplayName(
      "a signed-off month end renders one balanced statement per participant, redelivery-safe")
  void fansOutBalancedStatementsAndSurvivesRedelivery() {
    UUID organizationId = UUID.randomUUID();
    UUID fundId = UUID.randomUUID();
    long unitLedgerId = Math.abs(UUID.randomUUID().getMostSignificantBits());
    String risingRef = "rising-" + UUID.randomUUID();
    String fallingRef = "falling-" + UUID.randomUUID();
    // A random month, not a fixed one: workflow.snapshot_run_one_of_record_per_date allows at
    // most one COMPLETED run per business date, and Postgres is reused across local runs
    // (AGENTS.md §4) — a fixed date collides with itself on the second run.
    java.time.YearMonth closingMonth = randomYearMonth();
    LocalDate openingDate = closingMonth.minusMonths(1).atEndOfMonth();
    LocalDate closingDate = closingMonth.atEndOfMonth();
    String period = closingMonth.toString();
    Instant closingRunStartedAt = closingDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    Instant signedOffAt = closingRunStartedAt.plusSeconds(300);

    insertOrganization(organizationId);
    insertFund(fundId, unitLedgerId);
    // The contribution's own strike NAV must fall strictly inside (openingDate, closingDate] for
    // JdbcContributionSource to attribute it to this period — ten days before the close, safely
    // inside the closing month regardless of its length.
    long midMonthNavVersionId = insertFundNav(fundId, closingDate.minusDays(10), 100_000_000L);
    insertFundNav(fundId, closingDate, 100_000_000L);
    UUID risingParticipantId = insertParticipant(organizationId, risingRef);
    UUID fallingParticipantId = insertParticipant(organizationId, fallingRef);

    // Opening snapshot: both participants start at 45,100.00.
    String openingKey = "it/opening-" + UUID.randomUUID() + ".ndjson.gz";
    putSnapshotObject(
        openingKey,
        positionLine(organizationId, risingRef, fundId, unitLedgerId, 45_100_00L),
        positionLine(organizationId, fallingRef, fundId, unitLedgerId, 45_100_00L));
    insertCompletedSnapshotRun(
        openingDate, openingKey, openingDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

    // A contribution of 1,800.00 (900 employee, 900 employer) for the rising participant only,
    // struck well before the closing snapshot's own pass began. Both funds carry NAV
    // 1.00000000 (nav_scaled = 1e8), so unitsScaled = cents * 1e6 reproduces exactly that many
    // cents through Money.value(units, nav) — see unitsForCentsAtParNav.
    UUID batchId = insertContributionBatch(organizationId);
    insertAllotment(
        organizationId,
        batchId,
        risingRef,
        "EMPLOYEE",
        fundId,
        midMonthNavVersionId,
        unitsForCentsAtParNav(900_00L),
        closingRunStartedAt.minusSeconds(3600));
    insertAllotment(
        organizationId,
        batchId,
        risingRef,
        "EMPLOYER",
        fundId,
        midMonthNavVersionId,
        unitsForCentsAtParNav(900_00L),
        closingRunStartedAt.minusSeconds(3600));
    // A LATE allotment, in a SECOND batch (workflow.allotment's primary key is (organization_id,
    // batch_id, participant_internal_ref, source), so a second EMPLOYEE row needs a second
    // batch_id): struck after the closing snapshot's pass already began, with a valuation_date
    // that still falls inside this period. Its units are not in the closing snapshot below (built
    // independently of this row), so it must be excluded from the contributions total or the
    // waterfall would silently absorb it as a fabricated loss — the regression this fixture
    // proves is fixed.
    UUID lateBatchId = insertContributionBatch(organizationId);
    insertAllotment(
        organizationId,
        lateBatchId,
        risingRef,
        "EMPLOYEE",
        fundId,
        midMonthNavVersionId,
        unitsForCentsAtParNav(500_00L),
        closingRunStartedAt.plusSeconds(60));

    // Closing snapshot: the rising participant gained (opening + contribution + market movement),
    // the falling one lost value with no contribution at all.
    String closingKey = "it/closing-" + UUID.randomUUID() + ".ndjson.gz";
    putSnapshotObject(
        closingKey,
        positionLine(organizationId, risingRef, fundId, unitLedgerId, 47_382_15L),
        positionLine(organizationId, fallingRef, fundId, unitLedgerId, 40_000_00L));
    UUID closingRunId = insertCompletedSnapshotRun(closingDate, closingKey, closingRunStartedAt);

    String closeSignedOffMessage =
        closeSignedOffJson(closingDate, signedOffAt, closingRunId, closingKey, 2, true);

    publish(closeSignedOffMessage, closingDate);

    // assertThat(Optional).isPresent() throws AssertionError (which Awaitility retries on),
    // unlike Optional#orElseThrow's NoSuchElementException, which it would propagate immediately.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(runs.find(period)).isPresent();
              StatementRun run = runs.find(period).get();
              assertThat(run.status()).isEqualTo(StatementRunStatus.COMPLETED);
              assertThat(run.completedCount()).hasValue(2L);
            });

    JsonNode risingStatement = readStatement(period, risingParticipantId);
    // 90_000 + 90_000 only — the late 50_000 allotment (posted after the closing snapshot's
    // pass began) must not be counted, proving the read-consistency cutoff actually excludes it.
    assertBalances(risingStatement, 45_100_00L, 90_000L, 90_000L, 0L, 47_382_15L);
    assertThat(risingStatement.get("marketMovementCents").asLong())
        .as("the rising participant's plug")
        .isEqualTo(47_382_15L - 45_100_00L - 90_000L - 90_000L);

    JsonNode fallingStatement = readStatement(period, fallingParticipantId);
    assertBalances(fallingStatement, 45_100_00L, 0L, 0L, 0L, 40_000_00L);
    assertThat(fallingStatement.get("marketMovementCents").asLong())
        .as("a down period must produce a negative, unclamped market movement")
        .isEqualTo(40_000_00L - 45_100_00L)
        .isNegative();

    // Redelivery: the same event, republished, must not change a single rendered byte or double
    // the live Redis count. Reading the counter directly, not workflow.statement_run's
    // completed_count: that column is frozen the moment the run first settles to COMPLETED and
    // would read 2 even if the redelivery had silently double-counted underneath it.
    byte[] risingBefore = getStatementBytes(period, risingParticipantId);
    publish(closeSignedOffMessage, closingDate);

    await()
        .pollDelay(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(completionCounter.currentCount(period)).hasValue(2L));
    assertThat(getStatementBytes(period, risingParticipantId)).isEqualTo(risingBefore);
  }

  private void assertBalances(
      JsonNode statement,
      long opening,
      long employee,
      long employer,
      long voluntary,
      long closing) {
    assertThat(statement.get("openingBalanceCents").asLong()).isEqualTo(opening);
    assertThat(statement.get("contributionsEmployeeCents").asLong()).isEqualTo(employee);
    assertThat(statement.get("contributionsEmployerCents").asLong()).isEqualTo(employer);
    assertThat(statement.get("contributionsVoluntaryCents").asLong()).isEqualTo(voluntary);
    assertThat(statement.get("closingBalanceCents").asLong()).isEqualTo(closing);
    long reconstructed =
        opening
            + employee
            + employer
            + voluntary
            - statement.get("withdrawalsCents").asLong()
            - statement.get("feesCents").asLong()
            - statement.get("taxWithheldCents").asLong()
            + statement.get("marketMovementCents").asLong();
    assertThat(reconstructed).isEqualTo(closing);
  }

  private JsonNode readStatement(String period, UUID participantId) {
    byte[] bytes = getStatementBytes(period, participantId);
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] getStatementBytes(String period, UUID participantId) {
    String key = "statements/period=" + period + "/participant=" + participantId + ".json";
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      return s3.getObject(
              software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                  .bucket(STATEMENT_BUCKET)
                  .key(key)
                  .build())
          .readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void publish(String json, LocalDate businessDate) {
    rabbitTemplate.convertAndSend("close.signed-off.v1", businessDate.toString(), json);
  }

  private static java.time.YearMonth randomYearMonth() {
    // Wide range, kept well under year 9999 (timestamptz), so repeated local runs against the
    // reused Postgres fixture do not collide on snapshot_run_one_of_record_per_date or
    // workflow.statement_run's primary key.
    int year = 1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(8000);
    int month = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12);
    return java.time.YearMonth.of(year, month);
  }

  private static String closeSignedOffJson(
      LocalDate businessDate,
      Instant signedOffAt,
      UUID snapshotRunId,
      String snapshotObjectKey,
      long positionCount,
      boolean monthEnd) {
    return """
        {"schemaVersion":"v1","businessDate":"%s","signedOffAt":"%s",
         "reconciliationRunId":"%s","custodianRunId":"%s","snapshotRunId":"%s",
         "snapshotObjectKey":"%s","snapshotManifestKey":"unused.json",
         "snapshotPositionCount":%d,"lastBusinessDayOfMonth":%b}"""
        .formatted(
            businessDate,
            signedOffAt,
            UUID.randomUUID(),
            UUID.randomUUID(),
            snapshotRunId,
            snapshotObjectKey,
            positionCount,
            monthEnd);
  }

  private static String positionLine(
      UUID orgId, String participantRef, UUID fundId, long unitLedgerId, long valueMinorUnits) {
    return """
        {"organizationId":"%s","participantInternalRef":"%s","fundId":"%s","unitLedgerId":%d,\
        "unitsScaled":100000000,"navScaled":100000000,"navVersionId":1,"currency":"MXN",\
        "valueMinorUnits":%d,"costBasisMinorUnits":0,"unrealisedGainMinorUnits":0}"""
        .formatted(orgId, participantRef, fundId, unitLedgerId, valueMinorUnits);
  }

  private void putSnapshotObject(String key, String... lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      for (String line : lines) {
        gzip.write(line.getBytes(StandardCharsets.UTF_8));
        gzip.write('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      s3.putObject(
          PutObjectRequest.builder().bucket(SNAPSHOT_BUCKET).key(key).build(),
          RequestBody.fromBytes(out.toByteArray()));
    }
  }

  private void insertOrganization(UUID organizationId) {
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Statement Worker IT SA de CV', 'ACTIVE', now(), now())
            """)
        .param("organizationId", organizationId)
        .param("taxId", "SWI" + UUID.randomUUID().toString().substring(0, 10))
        .update();
  }

  private void insertFund(UUID fundId, long unitLedgerId) {
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", fundId)
        .param("unitLedgerId", unitLedgerId)
        .param("unitScale", dev.fondaria.common.Units.SCALE)
        .update();
  }

  private long insertFundNav(UUID fundId, LocalDate date, long navScaled) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :date, :navScaled, 0, 'IT_FIXTURE', now())
            RETURNING nav_version_id
            """)
        .param("fundId", fundId)
        .param("date", date)
        .param("navScaled", navScaled)
        .query(Long.class)
        .single();
  }

  private UUID insertParticipant(UUID organizationId, String participantRef) {
    jdbc.sql(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                created_at, updated_at)
            VALUES (:organizationId, :internalRef, 'PLAN-A', :payrollRef,
                    'ACTIVE', 'ACTIVE', DATE '2024-01-15', DATE '2024-02-01', now(), now())
            """)
        .param("organizationId", organizationId)
        .param("internalRef", participantRef)
        .param("payrollRef", "PR-" + UUID.randomUUID())
        .update();
    return jdbc.sql(
            "SELECT participant_id FROM master.participant"
                + " WHERE organization_id = :organizationId AND participant_internal_ref = :internalRef")
        .param("organizationId", organizationId)
        .param("internalRef", participantRef)
        .query(UUID.class)
        .single();
  }

  private UUID insertCompletedSnapshotRun(
      LocalDate businessDate, String positionsObjectKey, Instant startedAt) {
    UUID runId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.snapshot_run
              (run_id, business_date, status, started_at, completed_at, duration_millis,
               position_count, fund_count, positions_object_key, positions_sha256,
               manifest_object_key, manifest_sha256)
            VALUES (:runId, :businessDate, 'COMPLETED', :startedAt, :startedAt, 1,
                    2, 1, :positionsObjectKey, :hash, 'unused-manifest.json', :hash)
            """)
        .param("runId", runId)
        .param("businessDate", businessDate)
        .param("startedAt", java.sql.Timestamp.from(startedAt))
        .param("positionsObjectKey", positionsObjectKey)
        .param("hash", "0".repeat(64))
        .update();
    return runId;
  }

  private UUID insertContributionBatch(UUID organizationId) {
    UUID batchId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch
              (batch_id, organization_id, object_key, checksum_sha256, status, submitted_at,
               status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'ALLOTTED', now(), now())
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId)
        .param("objectKey", "it-fixture/" + batchId)
        .param("checksum", "0".repeat(64))
        .update();
    return batchId;
  }

  /**
   * At {@code nav_scaled == 1e8} (NAV 1.00000000), this is the units that price back to exactly
   * {@code cents} through {@code Money.value(units, nav)}: cents * 1e6.
   */
  private static long unitsForCentsAtParNav(long cents) {
    return cents * 1_000_000L;
  }

  private void insertAllotment(
      UUID organizationId,
      UUID batchId,
      String participantRef,
      String source,
      UUID fundId,
      long navVersionId,
      long unitsScaled,
      Instant allottedAt) {
    jdbc.sql(
            """
            INSERT INTO workflow.allotment
              (organization_id, batch_id, participant_internal_ref, source, fund_id,
               nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            VALUES (:organizationId, :batchId, :participantRef, :source, :fundId, :navVersionId,
                    :unitsScaled, true, gen_random_uuid(), :allottedAt)
            """)
        .param("organizationId", organizationId)
        .param("batchId", batchId)
        .param("participantRef", participantRef)
        .param("source", source)
        .param("fundId", fundId)
        .param("navVersionId", navVersionId)
        .param("unitsScaled", unitsScaled)
        .param("allottedAt", java.sql.Timestamp.from(allottedAt))
        .update();
  }
}
