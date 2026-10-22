package dev.fondaria.statementworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.statementworker.render.RedisCompletionCounter;
import dev.fondaria.statementworker.schedule.StatementJob;
import dev.fondaria.statementworker.support.ServiceIntegrationTest;
import dev.fondaria.testing.ObjectStoreFixture;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Task 7.5b's own acceptance criterion, taken literally: "a worker crash mid-run does not require
 * restarting the whole batch — only its unfinished job messages are redelivered". This publishes
 * exactly one {@code statement.jobs.v1} message twice, bypassing the scheduler entirely (which is
 * what a real crash-and-redeliver looks like: the scheduler already published the whole month's
 * jobs once, and only one of them gets redelivered), and asserts the render is idempotent —
 * identical object bytes, the completion counter incremented exactly once.
 */
@SpringBootTest
class StatementJobRedeliveryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private RedisCompletionCounter completionCounter;
  @Autowired private RabbitTemplate rabbitTemplate;

  @Test
  @DisplayName("redelivering one statement.jobs.v1 message renders once, not twice")
  void redeliveringOneJobMessageIsIdempotent() {
    UUID organizationId = UUID.randomUUID();
    String participantRef = "redelivery-" + UUID.randomUUID();
    UUID participantId = insertParticipant(organizationId, participantRef);
    // A fresh random period, not a fixed one: the Redis fixture is reused across local runs
    // (docs/ENGINEERING-RULES.md §4), and a fixed period's completion set would accumulate one
    // member per prior
    // run, making the "exactly 1" assertion below fail on any run but the first.
    java.time.YearMonth randomMonth =
        java.time.YearMonth.of(
            1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(8000),
            1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12));
    String period = randomMonth.toString();

    StatementJob job =
        new StatementJob(
            new OrganizationId(organizationId),
            participantRef,
            period,
            randomMonth.atEndOfMonth().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            45_100_00L,
            90_000L,
            90_000L,
            0L,
            47_382_15L,
            CurrencyCode.MXN);

    publish(job);
    publish(job);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(completionCounter.currentCount(period)).hasValue(1L));

    byte[] bytes = getStatementBytes(period, participantId);
    assertThat(bytes).asString().contains("\"marketMovementCents\"");
  }

  private void publish(StatementJob job) {
    rabbitTemplate.convertAndSend("statement.jobs.v1", job.participantKey().asKey(), job.toJson());
  }

  private byte[] getStatementBytes(String period, UUID participantId) {
    String key = "statements/period=" + period + "/participant=" + participantId + ".json";
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      return s3.getObject(GetObjectRequest.builder().bucket(STATEMENT_BUCKET).key(key).build())
          .readAllBytes();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private UUID insertParticipant(UUID organizationId, String participantRef) {
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Redelivery IT SA de CV', 'ACTIVE', now(), now())
            """)
        .param("organizationId", organizationId)
        .param("taxId", "RDI" + UUID.randomUUID().toString().substring(0, 10))
        .update();
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
}
