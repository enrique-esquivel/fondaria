package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL behind {@link ContributionBatchRepository}, and what the database itself enforces,
 * against a real PostgreSQL. What the service <i>decides</i> is asserted without a database in
 * {@link BatchValidationRunnerTest}; the constraints, the {@code SKIP LOCKED} claim and the {@code
 * jsonb} round trip can only be asserted here.
 *
 * <p>Every test uses an organization and an object key it created itself. Fixtures are reused
 * across runs (AGENTS.md §4), so anything asserted over a whole table would be asserted over
 * whatever earlier runs left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcContributionBatchRepositoryIntegrationTest extends ServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

  /**
   * A limit big enough that no leftover can crowd this test's batch out of a sweep.
   *
   * <p>Fixtures are reused across runs (AGENTS.md §4), so {@code workflow.contribution_batch} still
   * holds whatever earlier runs left claimable, and the sweep orders oldest-first. A modest limit
   * would make "was my batch claimed" depend on how many times this suite had been run before —
   * intermittent, and about money. Claiming the leftovers too is harmless; every assertion below is
   * over an id this test created.
   */
  private static final int CLAIM_EVERYTHING = 1_000_000;

  @Autowired private ContributionBatchRepository repository;
  @Autowired private OrganizationService organizations;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("both tables are in the workflow schema, and the batch lifecycle may move")
  void livesInTheWorkflowSchema() {
    assertThat(privilege("workflow.contribution_batch", "SELECT")).isTrue();
    assertThat(privilege("workflow.contribution_batch", "INSERT")).isTrue();
    assertThat(privilege("workflow.contribution_batch", "UPDATE"))
        .as("workflow holds a status lifecycle, which moves in place (ADR-0011)")
        .isTrue();
    assertThat(privilege("workflow.contribution_batch_cash_claim", "INSERT")).isTrue();
  }

  @Test
  @DisplayName("the declared total is bigint cents, never numeric or floating point")
  void declaredTotalIsAnIntegerColumn() {
    assertThat(columnType("contribution_batch", "declared_total_minor_units")).isEqualTo("bigint");
  }

  @Test
  @DisplayName("the claim table stores no amount at all")
  void theClaimTableHoldsNoMoney() {
    // Copying an amount here would create a second, staler answer to a question
    // TigerBeetle already answers correctly (ADR-0008 invariant 1).
    List<String> columns =
        jdbc.sql(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'workflow'
                   AND table_name = 'contribution_batch_cash_claim'
                """)
            .query(String.class)
            .list();

    assertThat(columns)
        .containsExactlyInAnyOrder("organization_id", "transfer_id", "batch_id", "claimed_at");
  }

  @Test
  @DisplayName("a batch round-trips, and one file is recorded only once")
  void roundTripsAndIsSubmittedOnce() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = received(organizationId, "file.csv");

    ContributionBatch first = repository.submit(batch);
    ContributionBatch again = repository.submit(batch);

    assertThat(first.batchId()).isEqualTo(batch.batchId());
    assertThat(first.status()).isEqualTo(ContributionBatchStatus.RECEIVED);
    assertThat(again.batchId()).isEqualTo(first.batchId());
    assertThat(again.submittedAt()).isEqualTo(first.submittedAt());
    assertThat(repository.find(organizationId, batch.batchId())).isPresent();
  }

  @Test
  @DisplayName("two concurrent submissions of one file produce one batch, not an error")
  void concurrentSubmissionsConverge() throws Exception {
    // The bug this guards: ON CONFLICT DO NOTHING with an inference specification
    // suppresses conflicts on that index alone, so a race would raise on the OTHER
    // unique constraint and turn a duplicate into a 500. It cost one in Task 3.3.
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = received(organizationId, "concurrent.csv");
    Callable<ContributionBatch> submit = () -> repository.submit(batch);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<ContributionBatch>> results = pool.invokeAll(List.of(submit, submit));
      ContributionBatch first = results.get(0).get();
      ContributionBatch second = results.get(1).get();

      assertThat(first.batchId()).isEqualTo(second.batchId());
      assertThat(countBatchesFor(organizationId)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a claim moves a batch to VALIDATING and stamps its lease")
  void claimingStampsALease() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "claimed.csv"));

    List<ContributionBatch> claimed =
        repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    assertThat(claimed)
        .filteredOn(candidate -> candidate.batchId().equals(batch.batchId()))
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.status()).isEqualTo(ContributionBatchStatus.VALIDATING);
              assertThat(candidate.validationStartedAt()).isPresent();
            });
  }

  @Test
  @DisplayName("a batch already claimed and still within its lease is not claimed again")
  void aFreshLeaseIsNotStolen() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "leased.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    List<ContributionBatch> second =
        repository.claimForValidation(
            NOW.plus(Duration.ofMinutes(1)),
            NOW.plus(Duration.ofMinutes(1)).minus(Duration.ofMinutes(10)),
            CLAIM_EVERYTHING);

    assertThat(second).extracting(ContributionBatch::batchId).doesNotContain(batch.batchId());
  }

  @Test
  @DisplayName("a batch stranded by a dead process is reclaimed once its lease goes stale")
  void aStaleLeaseIsReclaimed() {
    // The whole reason the lease is a column rather than in-JVM state: any instance can
    // recover a batch whose validator died.
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "stranded.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    Instant later = NOW.plus(Duration.ofMinutes(11));
    List<ContributionBatch> reclaimed =
        repository.claimForValidation(later, later.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    assertThat(reclaimed).extracting(ContributionBatch::batchId).contains(batch.batchId());
  }

  @Test
  @DisplayName("resolving writes the header and clears the lease")
  void resolvingKeepsTheDeclarationItWasSubmittedWith() {
    // The declaration is written by the INSERT and never by an UPDATE
    // (contribution-file-v2.md §1.1). This is the assertion that proves resolve() does
    // not restate it — a second chance to disagree with what the employer submitted —
    // and that the per-source totals survive the round trip, which the async validator
    // depends on because it reads this row and never sees the request.
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "resolved.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    boolean written =
        repository.resolve(
            withOutcome(batch, ContributionBatchStatus.PENDING_RECONCILIATION, Optional.empty()));

    assertThat(written).isTrue();
    assertThat(repository.find(organizationId, batch.batchId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.status()).isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
              assertThat(stored.validationStartedAt()).isEmpty();
              assertThat(stored.declaredTotalCents()).isEqualTo(750_000L);
              assertThat(stored.declared()).isEqualTo(declaration());
              assertThat(stored.declared().declaredCentsFor(ContributionSource.EMPLOYEE))
                  .isEqualTo(500_000L);
              assertThat(stored.declared().declaredCentsFor(ContributionSource.EMPLOYER))
                  .isEqualTo(250_000L);
              assertThat(stored.declared().declaredCentsFor(ContributionSource.VOLUNTARY)).isZero();
            });
  }

  @Test
  @DisplayName("a rejection's structured details survive the jsonb round trip as integers")
  void rejectionDetailsRoundTrip() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "rejected.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);

    repository.resolve(
        withOutcome(
            batch,
            ContributionBatchStatus.REJECTED,
            Optional.of(
                BatchRejection.of(
                    BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH,
                    "The declared EMPLOYER subtotal does not equal the sum of its rows.",
                    Map.of(
                        "source",
                        "EMPLOYER",
                        "declaredCents",
                        250_000L,
                        "actualCents",
                        249_900L)))));

    assertThat(repository.find(organizationId, batch.batchId()))
        .hasValueSatisfying(
            stored ->
                assertThat(stored.rejection())
                    .hasValueSatisfying(
                        rejection -> {
                          assertThat(rejection.code())
                              .isEqualTo(BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH);
                          assertThat(rejection.details())
                              .containsEntry("source", "EMPLOYER")
                              .containsEntry("declaredCents", 250_000)
                              .containsEntry("actualCents", 249_900);
                          assertThat(rejection.details().values())
                              .allSatisfy(
                                  value ->
                                      assertThat(value)
                                          .isNotInstanceOfAny(Double.class, Float.class));
                        }));
  }

  @Test
  @DisplayName("a batch that is no longer VALIDATING refuses a late outcome")
  void aLostLeaseCannotWriteItsAnswer() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "lost-lease.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);
    repository.resolve(
        withOutcome(batch, ContributionBatchStatus.PENDING_RECONCILIATION, Optional.empty()));

    boolean written =
        repository.resolve(
            withOutcome(
                batch,
                ContributionBatchStatus.REJECTED,
                Optional.of(
                    BatchRejection.of(BatchRejectionCode.MALFORMED_FILE, "a late answer"))));

    assertThat(written).isFalse();
    assertThat(repository.find(organizationId, batch.batchId()))
        .hasValueSatisfying(
            stored ->
                assertThat(stored.status())
                    .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION));
  }

  @Test
  @DisplayName("the database refuses a rejection code it has never heard of")
  void anUnknownRejectionCodeIsRefused() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "bad-code.csv"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE workflow.contribution_batch
                           SET status = 'REJECTED',
                               rejection_code = 'NOT_A_REAL_CODE',
                               rejection_message = 'x'
                         WHERE batch_id = :batchId
                        """)
                    .param("batchId", batch.batchId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("the database refuses a rejection reason on a batch that was not rejected")
  void aValidatedBatchCannotCarryARejection() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "stale-reason.csv"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE workflow.contribution_batch
                           SET rejection_code = 'MALFORMED_FILE',
                               rejection_message = 'x'
                         WHERE batch_id = :batchId
                        """)
                    .param("batchId", batch.batchId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("the database refuses a reconcilable batch with no declared total")
  void aReconcilableBatchMustKnowItsDeclaredTotal() {
    // Otherwise the gate would have nothing to compare the money against.
    //
    // Since contribution-file-v2.md §1.1 moved the declarations onto the submission, a
    // batch carries its declared total from the INSERT, so no ordinary path can reach
    // PENDING_RECONCILIATION without one. The constraint is asserted anyway, by clearing
    // the total first: it is the last line of defence for the one invariant the
    // reconciliation gate cannot work without, and a guard nothing exercises is a guard
    // nobody notices has been dropped.
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "no-total.csv"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE workflow.contribution_batch
                           SET status = 'PENDING_RECONCILIATION',
                               plan_id = NULL,
                               value_date = NULL,
                               declared_total_minor_units = NULL,
                               declared_employee_minor_units = NULL,
                               declared_employer_minor_units = NULL,
                               declared_voluntary_minor_units = NULL
                         WHERE batch_id = :batchId
                        """)
                    .param("batchId", batch.batchId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("the database refuses a zero or negative declared total")
  void aDeclaredTotalIsPositive() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "zero-total.csv"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE workflow.contribution_batch
                           SET declared_total_minor_units = 0,
                               plan_id = 'P',
                               value_date = DATE '2026-08-20',
                               declared_row_count = 1
                         WHERE batch_id = :batchId
                        """)
                    .param("batchId", batch.batchId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("the database refuses a checksum that is not lowercase hex")
  void aChecksumIsLowercaseHex() {
    OrganizationId organizationId = registerOrganization();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO workflow.contribution_batch (
                            batch_id, organization_id, object_key, checksum_sha256, status,
                            submitted_at, status_updated_at)
                        VALUES (:batchId, :organizationId, 'k', 'NOT-A-CHECKSUM', 'RECEIVED',
                                now(), now())
                        """)
                    .param("batchId", UUID.randomUUID())
                    .param("organizationId", organizationId.value())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("a batch is only findable by its own organization")
  void aBatchIsScopedToItsTenant() {
    OrganizationId organizationId = registerOrganization();
    OrganizationId other = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "tenant.csv"));

    assertThat(repository.find(other, batch.batchId())).isEmpty();
  }

  @Test
  @DisplayName("the pending-reconciliation listing covers only this organization")
  void pendingReconciliationIsScopedToItsTenant() {
    OrganizationId organizationId = registerOrganization();
    OrganizationId other = registerOrganization();
    ContributionBatch batch = repository.submit(received(organizationId, "pending.csv"));
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);
    repository.resolve(
        withOutcome(batch, ContributionBatchStatus.PENDING_RECONCILIATION, Optional.empty()));

    assertThat(repository.pendingReconciliation(organizationId))
        .extracting(ContributionBatch::batchId)
        .containsExactly(batch.batchId());
    assertThat(repository.pendingReconciliation(other)).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // Parts of a file too large to allot as a single linked chain (ADR-0017, V24)
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a part is recorded once, however many times staging is re-run")
  void aPartIsRecordedOnce() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "split.csv"));

    assertThat(repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2)).isTrue();
    assertThat(repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2))
        .as("a re-run of staging converges on the part that exists")
        .isFalse();
  }

  /**
   * V4's content address is a file's identity, and every part of a file shares it. The uniqueness
   * has to survive as a statement about submissions alone, or no file could ever be divided.
   */
  @Test
  @DisplayName("parts share their file's object key and checksum, and submissions stay unique")
  void partsShareTheContentAddressAndSubmissionsStayUnique() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "address.csv"));
    repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2);

    ContributionBatch part = repository.find(organizationId, partId(file, 2)).orElseThrow();
    assertThat(part.objectKey()).isEqualTo(file.objectKey());
    assertThat(part.checksumSha256()).isEqualTo(file.checksumSha256());

    // The file is still content-addressed: resubmitting it converges on the same row rather
    // than colliding with the part that now shares its address.
    assertThat(repository.submit(file).batchId()).isEqualTo(file.batchId());
  }

  @Test
  @DisplayName("a part is never claimed by the validation sweep or listed on the ageing report")
  void partsAreNeitherSweptNorAged() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "unswept.csv"));
    repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2);

    assertThat(
            repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING))
        .extracting(ContributionBatch::batchId)
        .contains(file.batchId())
        .doesNotContain(partId(file, 2));
    assertThat(repository.pendingReconciliation(organizationId))
        .extracting(ContributionBatch::batchId)
        .doesNotContain(partId(file, 2));
  }

  @Test
  @DisplayName("staging a file stages its parts at the same instant, and only those still waiting")
  void stagingAFileStagesItsParts() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "cascade.csv"));
    repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2);
    repository.insertPartIfAbsent(part(file, 3), file.batchId(), 3);
    repository.claimForValidation(NOW, NOW.minus(Duration.ofMinutes(10)), CLAIM_EVERYTHING);
    Instant stagedAt = Instant.parse("2026-08-19T11:00:00Z");
    repository.resolve(
        withOutcome(file, ContributionBatchStatus.STAGED, Optional.empty())
            .withFileValidatedAt(NOW));

    assertThat(repository.stageParts(file.batchId(), stagedAt, stagedAt)).isEqualTo(2);
    for (int partNumber = 2; partNumber <= 3; partNumber++) {
      ContributionBatch staged =
          repository.find(organizationId, partId(file, partNumber)).orElseThrow();
      assertThat(staged.status()).isEqualTo(ContributionBatchStatus.STAGED);
      assertThat(staged.stagedAt()).contains(stagedAt);
    }

    // Write-once: a re-resolve of the file must not re-date a part that has been waiting to deal,
    // and must not drag an already-allotted part backwards.
    assertThat(repository.stageParts(file.batchId(), stagedAt.plusSeconds(3_600), NOW)).isZero();
    assertThat(repository.find(organizationId, partId(file, 2)).orElseThrow().stagedAt())
        .contains(stagedAt);
  }

  @Test
  @DisplayName("a file and its parts report the same progress, read through either id")
  void progressIsTheSameThroughEitherId() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "progress.csv"));
    repository.insertPartIfAbsent(part(file, 2), file.batchId(), 2);
    repository.insertPartIfAbsent(part(file, 3), file.batchId(), 3);

    BatchPartProgress fromFile =
        repository.progressOf(organizationId, file.batchId()).orElseThrow();
    BatchPartProgress fromPart =
        repository.progressOf(organizationId, partId(file, 3)).orElseThrow();

    assertThat(fromFile.fileBatchId()).isEqualTo(file.batchId());
    assertThat(fromFile.partCount()).isEqualTo(3);
    assertThat(fromFile.partNumber()).isEqualTo(1);
    assertThat(fromFile.allottedParts()).isZero();
    assertThat(fromPart.fileBatchId()).isEqualTo(file.batchId());
    assertThat(fromPart.partCount()).isEqualTo(3);
    assertThat(fromPart.partNumber()).isEqualTo(3);
  }

  @Test
  @DisplayName("an undivided file reports one part, and an unknown batch reports nothing")
  void progressForAnUndividedFileAndAnUnknownBatch() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "whole.csv"));

    assertThat(repository.progressOf(organizationId, file.batchId()))
        .hasValueSatisfying(progress -> assertThat(progress.isSplit()).isFalse());
    assertThat(repository.progressOf(organizationId, UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("a part is never a part of itself, and is never numbered part 1")
  void theDatabaseRefusesAMalformedPart() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "malformed.csv"));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE workflow.contribution_batch
                           SET parent_batch_id = batch_id, part_number = 2
                         WHERE batch_id = :batchId
                        """)
                    .param("batchId", file.batchId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("markFileValidated persists fileValidatedAt and fails when not in VALIDATING")
  void markFileValidatedPersistsFileValidatedAt() {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch file = repository.submit(received(organizationId, "validate-mark.csv"));
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // Fails when still in RECEIVED
    assertThat(repository.markFileValidated(file.batchId(), now)).isFalse();

    // Claim it so status becomes VALIDATING
    List<ContributionBatch> claimed =
        repository.claimForValidation(now, now.minusSeconds(600), CLAIM_EVERYTHING);
    assertThat(claimed).extracting(ContributionBatch::batchId).contains(file.batchId());

    // Succeeds in VALIDATING
    assertThat(repository.markFileValidated(file.batchId(), now)).isTrue();

    ContributionBatch updated = repository.find(organizationId, file.batchId()).orElseThrow();
    assertThat(updated.fileValidatedAt()).contains(now);
    assertThat(updated.status()).isEqualTo(ContributionBatchStatus.VALIDATING);
  }

  @Test
  @DisplayName("releaseValidation resets lease to RECEIVED or PENDING_RECONCILIATION")
  void releaseValidationResetsLease() {
    OrganizationId organizationId = registerOrganization();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // Case 1: unvalidated batch (fileValidatedAt is empty) releases back to RECEIVED
    ContributionBatch unvalidated = repository.submit(received(organizationId, "unvalidated.csv"));
    repository.claimForValidation(now, now.minusSeconds(600), CLAIM_EVERYTHING);
    assertThat(repository.releaseValidation(unvalidated.batchId(), now.plusSeconds(5))).isTrue();

    ContributionBatch releasedUnvalidated =
        repository.find(organizationId, unvalidated.batchId()).orElseThrow();
    assertThat(releasedUnvalidated.status()).isEqualTo(ContributionBatchStatus.RECEIVED);
    assertThat(releasedUnvalidated.validationStartedAt()).isEmpty();

    // Case 2: validated batch (fileValidatedAt is present) releases back to PENDING_RECONCILIATION
    ContributionBatch validated = repository.submit(received(organizationId, "validated.csv"));
    repository.claimForValidation(now, now.minusSeconds(600), CLAIM_EVERYTHING);
    repository.markFileValidated(validated.batchId(), now.plusSeconds(2));
    assertThat(repository.releaseValidation(validated.batchId(), now.plusSeconds(5))).isTrue();

    ContributionBatch releasedValidated =
        repository.find(organizationId, validated.batchId()).orElseThrow();
    assertThat(releasedValidated.status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(releasedValidated.validationStartedAt()).isEmpty();
    assertThat(releasedValidated.fileValidatedAt()).contains(now.plusSeconds(2));
  }

  @Test
  @DisplayName("claimForValidation excludes batches whose child parts are STAGED or ALLOTTED")
  void claimForValidationExcludesBatchesWithStagedOrAllottedParts() {
    OrganizationId organizationId = registerOrganization();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    ContributionBatch file = repository.submit(received(organizationId, "split-staged.csv"));

    ContributionBatch part2 = part(file, 2);
    repository.insertPartIfAbsent(part2, file.batchId(), 2);

    // Move child part to STAGED directly in the database
    jdbc.sql("UPDATE workflow.contribution_batch SET status = 'STAGED' WHERE batch_id = :id")
        .param("id", part2.batchId())
        .update();

    // File batch is still RECEIVED, but its child part is STAGED -- must NOT be claimed!
    List<ContributionBatch> claimed =
        repository.claimForValidation(now, now.minusSeconds(600), CLAIM_EVERYTHING);
    assertThat(claimed).extracting(ContributionBatch::batchId).doesNotContain(file.batchId());
  }

  private static UUID partId(ContributionBatch file, int partNumber) {
    return ContributionBatchId.derivePart(file.batchId(), partNumber);
  }

  /**
   * A part as {@code ContributionStagingService} builds one: its file's content address, its own
   * rows' totals, and no cash of its own.
   */
  private static ContributionBatch part(ContributionBatch file, int partNumber) {
    return new ContributionBatch(
        partId(file, partNumber),
        file.organizationId(),
        file.objectKey(),
        file.checksumSha256(),
        ContributionBatchStatus.PENDING_RECONCILIATION,
        new BatchDeclaration(
            file.declared().planId(),
            file.declared().payrollPeriod(),
            file.declared().valueDate(),
            450_000L,
            1,
            Map.of(
                ContributionSource.EMPLOYEE, 300_000L,
                ContributionSource.EMPLOYER, 150_000L,
                ContributionSource.VOLUNTARY, 0L)),
        Optional.empty(),
        Optional.of(NOW),
        Optional.empty(),
        Optional.empty(),
        file.submittedAt(),
        NOW);
  }

  private ContributionBatch received(OrganizationId organizationId, String name) {
    String objectKey = "contribution-batches/" + organizationId.value() + "/2026/08/" + name;
    String checksum =
        HexFormat.of()
            .formatHex(
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .substring(0, 64);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return new ContributionBatch(
        ContributionBatchId.derive(organizationId, objectKey, checksum),
        organizationId,
        objectKey,
        checksum,
        ContributionBatchStatus.RECEIVED,
        declaration(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        now,
        now);
  }

  /**
   * What a submission declares. Present from the moment a batch exists — {@code
   * contribution-file-v2.md} §1.1 — so unlike v1 there is no "before the header was read" state to
   * fixture around.
   */
  private static BatchDeclaration declaration() {
    return new BatchDeclaration(
        "PLAN-ACME-CAJA-1",
        "2026-08-B",
        LocalDate.of(2026, 8, 20),
        750_000L,
        2,
        Map.of(
            ContributionSource.EMPLOYEE,
            500_000L,
            ContributionSource.EMPLOYER,
            250_000L,
            ContributionSource.VOLUNTARY,
            0L));
  }

  private static ContributionBatch withOutcome(
      ContributionBatch batch, ContributionBatchStatus status, Optional<BatchRejection> rejection) {
    return new ContributionBatch(
        batch.batchId(),
        batch.organizationId(),
        batch.objectKey(),
        batch.checksumSha256(),
        status,
        batch.declared(),
        rejection,
        batch.fileValidatedAt(),
        Optional.empty(),
        Optional.empty(),
        batch.submittedAt(),
        Instant.now().truncatedTo(ChronoUnit.MILLIS));
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

  private int countBatchesFor(OrganizationId organizationId) {
    return jdbc.sql("SELECT count(*) FROM workflow.contribution_batch WHERE organization_id = :id")
        .param("id", organizationId.value())
        .query(Integer.class)
        .single();
  }

  private String columnType(String table, String column) {
    return jdbc.sql(
            """
            SELECT data_type FROM information_schema.columns
             WHERE table_schema = 'workflow' AND table_name = :table AND column_name = :column
            """)
        .param("table", table)
        .param("column", column)
        .query(String.class)
        .single();
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
