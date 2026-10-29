package dev.fondaria.allotmentjob.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.allotmentjob.support.ServiceIntegrationTest;
import dev.fondaria.common.OrganizationId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link JdbcStagedBatchRepository#rowsFor} against a real PostgreSQL: it returns one
 * organization's rows of one batch, in participant order, through the primary key rather than a
 * sequential scan of the whole table (load run 6 measured 86 ms per call at 3 days of rows).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcStagedBatchRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private StagedBatchRepository batches;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("rowsFor returns the batch's rows for that organization only, in participant order")
  void rowsForIsScopedToTheOrganizationAndOrdered() {
    OrganizationId organizationId = registerOrganization();
    OrganizationId otherOrganizationId = registerOrganization();
    UUID batchId = UUID.randomUUID();
    stageBatch(organizationId, batchId);
    stageRow(organizationId, batchId, "P-003", 3_00L);
    stageRow(organizationId, batchId, "P-001", 1_00L);
    stageRow(organizationId, batchId, "P-002", 2_00L);
    // Another tenant's row carrying the same batch id must never be read as this one's.
    stageRow(otherOrganizationId, batchId, "P-000", 9_00L);

    List<StagedRow> rows = batches.rowsFor(organizationId, batchId);

    assertThat(rows)
        .extracting(StagedRow::participantInternalRef)
        .containsExactly("P-001", "P-002", "P-003");
    assertThat(rows).allSatisfy(row -> assertThat(row.organizationId()).isEqualTo(organizationId));
    assertThat(rows.getFirst().amounts().get(ContributionSource.EMPLOYEE)).isEqualTo(1_00L);
  }

  /**
   * The table is far too small here for the planner to prefer an index on cost, so sequential scans
   * are switched off for this one transaction: what is asserted is that the primary key can serve
   * the query on both leading columns. With {@code batch_id} alone the planner could at best walk
   * the whole index and filter, never seek.
   */
  @Test
  @DisplayName("rowsFor seeks the primary key on (organization_id, batch_id)")
  void rowsForSeeksThePrimaryKey() {
    String plan =
        transactions.execute(
            status -> {
              jdbc.sql("SET LOCAL enable_seqscan = off").update();
              return String.join(
                  "\n",
                  jdbc.sql(
                          """
                          EXPLAIN
                          SELECT organization_id, batch_id, participant_internal_ref,
                                 employee_minor_units, employer_minor_units, voluntary_minor_units
                            FROM workflow.contribution_batch_row
                           WHERE organization_id = :organizationId
                             AND batch_id = :batchId
                           ORDER BY participant_internal_ref
                          """)
                      .param("organizationId", UUID.randomUUID())
                      .param("batchId", UUID.randomUUID())
                      .query(String.class)
                      .list());
            });

    assertThat(plan)
        .contains("contribution_batch_row_pk")
        .containsPattern("Index Cond: \\(\\(organization_id = .*\\) AND \\(batch_id = .*\\)\\)")
        .doesNotContain("Sort");
  }

  private OrganizationId registerOrganization() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO master.organization (
                organization_id, tax_id_scheme, tax_id, legal_name, status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Acme S.A. de C.V.', 'ACTIVE', :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", "RFC-" + UUID.randomUUID())
        .param("now", Timestamp.from(now))
        .update();
    return organizationId;
  }

  private void stageBatch(OrganizationId organizationId, UUID batchId) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch (
                batch_id, organization_id, object_key, checksum_sha256, status,
                plan_id, payroll_period, value_date,
                declared_total_minor_units, declared_row_count,
                declared_employee_minor_units, declared_employer_minor_units,
                declared_voluntary_minor_units,
                staged_at, submitted_at, status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'STAGED',
                    'PLAN-ACME-CAJA-1', '2026-08-B', :valueDate,
                    600, 3, 600, 0, 0,
                    :now, :now, :now)
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "contribution-batches/" + batchId + ".csv")
        .param("checksum", "a".repeat(64))
        .param("valueDate", LocalDate.of(2026, 8, 19))
        .param("now", Timestamp.from(now))
        .update();
  }

  private void stageRow(
      OrganizationId organizationId, UUID batchId, String participantRef, long employeeCents) {
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch_row (
                organization_id, batch_id, participant_internal_ref, participant_payroll_ref,
                plan_id, payroll_period, value_date, employee_minor_units, employer_minor_units,
                voluntary_minor_units, recorded_at)
            VALUES (:organizationId, :batchId, :participantRef, :participantRef,
                    'PLAN-ACME-CAJA-1', '2026-08-B', :valueDate, :employeeCents, 0, 0, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .param("participantRef", participantRef)
        .param("valueDate", LocalDate.of(2026, 8, 19))
        .param("employeeCents", employeeCents)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }
}
