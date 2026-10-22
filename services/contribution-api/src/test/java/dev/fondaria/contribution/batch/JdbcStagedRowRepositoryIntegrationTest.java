package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL behind {@link StagedRowRepository}, and the schema layout ADR-0011 requires, against a
 * real PostgreSQL. What the service <i>decides</i> is asserted without a database in {@code
 * BatchValidationRunnerTest}; what the database <i>enforces</i> — above all, that a staged row
 * cannot be rewritten — can only be asserted here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcStagedRowRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private StagedRowRepository stagedRows;
  @Autowired private ContributionBatchRepository batches;
  @Autowired private OrganizationService organizations;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("the application role may insert and read a staged row, but never rewrite one")
  void aStagedRowIsImmutableByGrant() {
    assertThat(privilege("workflow.contribution_batch_row", "INSERT")).isTrue();
    assertThat(privilege("workflow.contribution_batch_row", "SELECT")).isTrue();
    assertThat(privilege("workflow.contribution_batch_row", "UPDATE"))
        .as("a correction is a new staged batch referencing the original, never an edit")
        .isFalse();
    assertThat(privilege("workflow.contribution_batch_row", "DELETE")).isFalse();
  }

  @Test
  @DisplayName("insertIfAbsent is conditional, keyed on the row's participant")
  void insertIsConditional() {
    OrganizationId organizationId = registerOrganization();
    UUID batchId = givenBatch(organizationId, "conditional.csv");
    StagedRow row = givenRow(organizationId, batchId, "P-001");

    assertThat(stagedRows.insertIfAbsent(row)).isTrue();
    assertThat(stagedRows.insertIfAbsent(row)).isFalse();

    assertThat(stagedRows.findByBatch(organizationId, batchId)).hasSize(1);
  }

  @Test
  @DisplayName("a row can only reference a batch that exists")
  void aRowReferencesARealBatch() {
    OrganizationId organizationId = registerOrganization();
    StagedRow row = givenRow(organizationId, UUID.randomUUID(), "P-001");

    assertThatThrownBy(() -> stagedRows.insertIfAbsent(row))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("findByBatch is scoped to its organization and its batch")
  void findByBatchIsScoped() {
    OrganizationId organizationId = registerOrganization();
    OrganizationId other = registerOrganization();
    UUID batchId = givenBatch(organizationId, "mine.csv");
    UUID otherBatchId = givenBatch(other, "theirs.csv");
    stagedRows.insertIfAbsent(givenRow(organizationId, batchId, "P-001"));
    stagedRows.insertIfAbsent(givenRow(other, otherBatchId, "P-001"));

    assertThat(stagedRows.findByBatch(organizationId, batchId)).hasSize(1);
    assertThat(stagedRows.findByBatch(other, batchId)).isEmpty();
  }

  @Test
  @DisplayName(
      "find(organization, period, status) only returns rows whose batch carries that status")
  void findFiltersByTheParentBatchStatus() {
    OrganizationId organizationId = registerOrganization();
    UUID receivedBatch = givenBatch(organizationId, "received.csv");
    UUID stagedBatch =
        givenBatchWithStatus(organizationId, "staged.csv", ContributionBatchStatus.STAGED);
    stagedRows.insertIfAbsent(givenRow(organizationId, receivedBatch, "P-001"));
    stagedRows.insertIfAbsent(givenRow(organizationId, stagedBatch, "P-002"));

    List<StagedRow> found =
        stagedRows.find(
            organizationId, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED);

    assertThat(found).extracting(StagedRow::participantInternalRef).containsExactly("P-002");
  }

  private StagedRow givenRow(OrganizationId organizationId, UUID batchId, String internalRef) {
    return new StagedRow(
        organizationId,
        batchId,
        internalRef,
        "ACME-EMP-0142",
        ContributionFiles.PLAN_ID,
        ContributionFiles.PAYROLL_PERIOD,
        LocalDate.of(2026, 8, 20),
        Map.of(
            ContributionSource.EMPLOYEE,
            300_000L,
            ContributionSource.EMPLOYER,
            150_000L,
            ContributionSource.VOLUNTARY,
            0L),
        Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  private UUID givenBatch(OrganizationId organizationId, String name) {
    return givenBatchWithStatus(organizationId, name, ContributionBatchStatus.RECEIVED);
  }

  private UUID givenBatchWithStatus(
      OrganizationId organizationId, String name, ContributionBatchStatus status) {
    String objectKey = ContributionFiles.objectKey(organizationId, name);
    String checksum = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    ContributionBatch submitted =
        batches.submit(
            new ContributionBatch(
                ContributionBatchId.derive(organizationId, objectKey, checksum),
                organizationId,
                objectKey,
                checksum,
                ContributionBatchStatus.RECEIVED,
                ContributionFiles.workedExampleDeclaration(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now));
    if (status == ContributionBatchStatus.RECEIVED) {
      return submitted.batchId();
    }
    // Moves only this one batch to VALIDATING, scoped by its own id — claimForValidation
    // sweeps every claimable batch in the table, including ones other tests or earlier
    // runs left behind, and this suite must never touch a row it did not create.
    jdbc.sql(
            "UPDATE workflow.contribution_batch SET status = 'VALIDATING' WHERE batch_id = :batchId")
        .param("batchId", submitted.batchId())
        .update();
    batches.resolve(
        new ContributionBatch(
            submitted.batchId(),
            organizationId,
            objectKey,
            checksum,
            status,
            submitted.declared(),
            Optional.empty(),
            Optional.of(now),
            Optional.empty(),
            Optional.empty(),
            now,
            now));
    return submitted.batchId();
  }

  private OrganizationId registerOrganization() {
    return organizations
        .register(
            new RegisterOrganizationCommand(
                FreshTaxIdentifiers.fresh(),
                "Acme S.A. de C.V.",
                Optional.empty(),
                Optional.empty()))
        .organization()
        .organizationId();
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }
}
