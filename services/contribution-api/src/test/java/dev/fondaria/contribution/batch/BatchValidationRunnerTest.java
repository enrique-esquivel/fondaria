package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.config.BatchValidationProperties;
import dev.fondaria.contribution.participant.InMemoryParticipantRepository;
import dev.fondaria.contribution.participant.ParticipantEnrolmentService;
import dev.fondaria.contribution.plan.InMemoryPlanVersionRepository;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a validation sweep concludes, end to end, without a database or a container: the checks in
 * their contract order, the gate, and the three ways a batch can leave {@code VALIDATING}.
 *
 * <p>The distinction this suite exists to pin down is <b>rejected versus pending versus
 * retried</b>. They look similar in code and are completely different to an employer: one says your
 * file is wrong and is terminal, one says your money has not arrived and resolves itself, and one
 * says we could not read it and must not be visible as either.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BatchValidationRunnerTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 20);

  private final OrganizationId organization = new OrganizationId(UUID.randomUUID());
  private final AccountIdDeriver accountIds = new AccountIdDeriver(UUID.randomUUID());
  private final InMemoryContributionBatchRepository batches =
      new InMemoryContributionBatchRepository();
  private final InMemoryCashClaimRepository claims = new InMemoryCashClaimRepository();
  private final StubObjectStore objectStore = new StubObjectStore();
  private final StubLedgerPort ledger = new StubLedgerPort();
  private final InMemoryParticipantRepository participants = new InMemoryParticipantRepository();
  private final InMemoryStagedRowRepository stagedRows = new InMemoryStagedRowRepository(batches);
  private final InMemoryPlanVersionRepository planVersions = new InMemoryPlanVersionRepository();

  /**
   * The production row cap (4,093), so nothing in this class is split: every file here is a handful
   * of rows, and these tests are about which branch of validation runs. Splitting has its own tests
   * — {@code BatchPartitionerTest} for the arithmetic and {@code ContributionStagingServiceTest}
   * for what a split writes.
   */
  private final BatchValidationProperties properties =
      new BatchValidationProperties(Duration.ofSeconds(5), 10, Duration.ofMinutes(10), 4_093);

  private AccountId employerCollections;
  private BatchValidationRunner runner;

  @BeforeEach
  void setUp() {
    employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organization.scope());
    planVersions.publish(
        ContributionFiles.defaultPlanVersion(organization, FundId.of(UUID.randomUUID())));
    runner =
        new BatchValidationRunner(
            batches,
            claims,
            objectStore,
            new ReconciliationGate(claims, ledger, accountIds),
            new ContributionBatchValidator(planVersions),
            new ParticipantEnrolmentService(
                participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
            new ContributionStagingService(
                stagedRows,
                batches,
                properties,
                new TransactionTemplate(new DirectTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            properties,
            // No transaction manager in a unit test: the callback runs directly, and what
            // is being proved here is which branch runs, not that it committed. The
            // transactional guarantee is proved against a real PostgreSQL.
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private UUID givenSubmitted(String content) {
    return givenSubmitted(content, ContributionFiles.workedExampleDeclaration());
  }

  private UUID givenSubmitted(String content, BatchDeclaration declared) {
    String checksum = ContributionFiles.sha256Hex(content);
    String objectKey = ContributionFiles.objectKey(organization, "file.csv");
    objectStore.put(objectKey, content);
    UUID batchId = ContributionBatchId.derive(organization, objectKey, checksum);
    batches.submit(
        new ContributionBatch(
            batchId,
            organization,
            objectKey,
            checksum,
            ContributionBatchStatus.RECEIVED,
            declared,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));
    return batchId;
  }

  private TransferId givenReceipt(long cents) {
    TransferId transferId = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(transferId, VALUE_DATE, NOW);
    ledger.givenCashReceipt(transferId, employerCollections, cents);
    return transferId;
  }

  /**
   * A runner whose files are split at {@code rowsPerPart} (ADR-0017). Everything else is the field
   * wiring above, so what changes between this and {@link #runner} is the row cap alone.
   */
  private BatchValidationRunner runnerSplittingAt(int rowsPerPart) {
    BatchValidationProperties splitting =
        new BatchValidationProperties(
            Duration.ofSeconds(5), 10, Duration.ofMinutes(10), rowsPerPart);
    return new BatchValidationRunner(
        batches,
        claims,
        objectStore,
        new ReconciliationGate(claims, ledger, accountIds),
        new ContributionBatchValidator(planVersions),
        new ParticipantEnrolmentService(
            participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
        new ContributionStagingService(
            stagedRows,
            batches,
            splitting,
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW, ZoneOffset.UTC)),
        splitting,
        new TransactionTemplate(new DirectTransactionManager()),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /**
   * The point of ADR-0017 seen end to end inside this service: one file, one wire, one gate — and
   * several dealable batches at the end of it. The employer's experience of funding is unchanged;
   * what changed is how many batches their file became.
   */
  @Test
  void a_split_file_stages_every_one_of_its_parts_when_its_single_wire_reconciles() {
    UUID fileId =
        givenSubmitted(ContributionFiles.manyRows(7), ContributionFiles.manyRowsDeclaration(7));
    givenReceipt(ContributionFiles.manyRowsDeclaration(7).declaredTotalCents());

    runnerSplittingAt(3).sweep();

    ContributionBatch file = batches.get(fileId);
    assertThat(file.status()).isEqualTo(ContributionBatchStatus.STAGED);
    for (int partNumber = 2; partNumber <= 3; partNumber++) {
      ContributionBatch part = batches.get(ContributionBatchId.derivePart(fileId, partNumber));
      assertThat(part.status()).isEqualTo(ContributionBatchStatus.STAGED);
      // The same instant as the file's, because the cut-off test reads it (Task 21.1): every
      // part of one file deals on the date its file became dealable, not on whenever a
      // cascade happened to run.
      assertThat(part.stagedAt()).isEqualTo(file.stagedAt());
    }
  }

  /**
   * The other half of "the employer funds once". A part carries no cash claim, so if it were ever
   * gated on its own it would sit in {@code PENDING_RECONCILIATION} forever with its file's money
   * already spoken for.
   */
  @Test
  void a_split_files_parts_are_never_gated_and_the_wire_is_claimed_once_by_the_file() {
    UUID fileId =
        givenSubmitted(ContributionFiles.manyRows(7), ContributionFiles.manyRowsDeclaration(7));
    TransferId receipt =
        givenReceipt(ContributionFiles.manyRowsDeclaration(7).declaredTotalCents());

    BatchValidationRunner splitting = runnerSplittingAt(3);
    splitting.sweep();
    // A second sweep is where a part would be picked up if it were claimable at all.
    splitting.sweep();

    assertThat(claims.claimedBy(fileId)).containsExactly(receipt);
    assertThat(claims.claimedBy(ContributionBatchId.derivePart(fileId, 2))).isEmpty();
    assertThat(claims.claimedBy(ContributionBatchId.derivePart(fileId, 3))).isEmpty();
    assertThat(claims.unclaimed(organization)).isEmpty();
  }

  /**
   * Its rows are the whole file's, distributed across the parts and counted once. The staged set is
   * what allotment reads, so a row lost between parts is a participant who is never priced, and a
   * row in two parts is a participant priced twice.
   */
  @Test
  void a_split_file_stages_every_row_exactly_once_across_its_parts() {
    UUID fileId =
        givenSubmitted(ContributionFiles.manyRows(7), ContributionFiles.manyRowsDeclaration(7));
    givenReceipt(ContributionFiles.manyRowsDeclaration(7).declaredTotalCents());

    runnerSplittingAt(3).sweep();

    assertThat(
            stagedRows
                .find(
                    organization, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED)
                .stream()
                .map(StagedRow::participantInternalRef))
        .hasSize(7)
        .doesNotHaveDuplicates();
  }

  /** A file that fits in one chain is untouched by any of this — no part row is created at all. */
  @Test
  void a_file_that_fits_in_one_chain_produces_no_parts() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    runner.sweep();

    assertThat(batches.progressOf(organization, batchId))
        .hasValueSatisfying(progress -> assertThat(progress.isSplit()).isFalse());
  }

  @Test
  void a_sound_file_whose_cash_has_arrived_is_validated_and_claims_that_cash() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    TransferId receipt = givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batchId)).containsExactly(receipt);
    assertThat(claims.unclaimed(organization)).isEmpty();
  }

  @Test
  void the_declaration_is_on_the_batch_from_submission_not_from_parsing() {
    // contribution-file-v2.md §1.1: the declarations arrive with the submission, so they
    // are readable before the file has ever been fetched. This is the assertion that
    // pins it — and it is why a REJECTED batch can say which payroll period was refused
    // whatever stage it failed at.
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());

    BatchDeclaration declared = batches.get(batchId).declared();

    assertThat(declared.planId()).isEqualTo(ContributionFiles.PLAN_ID);
    assertThat(declared.payrollPeriod()).isEqualTo(ContributionFiles.PAYROLL_PERIOD);
    assertThat(declared.valueDate()).isEqualTo(VALUE_DATE);
    assertThat(declared.declaredTotalCents()).isEqualTo(750_000L);
    assertThat(declared.declaredRowCount())
        .as("the worked example's two participants, one row each")
        .isEqualTo(2);
    assertThat(declared.declaredCentsFor(ContributionSource.EMPLOYER)).isEqualTo(250_000L);
  }

  @Test
  void a_rejected_batch_still_reports_the_payroll_period_that_was_refused() {
    UUID batchId =
        givenSubmitted(
            ContributionFiles.singleRow(500L),
            ContributionFiles.declaration(500L, 9, 500L, 0L, 0L));

    runner.sweep();

    ContributionBatch rejected = batches.get(batchId);
    assertThat(rejected.status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(rejected.declared().payrollPeriod()).isEqualTo(ContributionFiles.PAYROLL_PERIOD);
    assertThat(rejected.declared().planId()).isEqualTo(ContributionFiles.PLAN_ID);
  }

  @Test
  void a_file_total_that_does_not_equal_the_cash_received_blocks_allotment() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    givenReceipt(740_000L);

    runner.sweep();

    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(claims.claimedBy(batchId)).isEmpty();
  }

  @Test
  void a_blocked_batch_is_not_a_rejection_and_carries_no_rejection_reason() {
    // The file is fine. Telling an employer its file was rejected would be a lie, and a
    // durable one, because REJECTED is terminal.
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(batches.get(batchId).rejection()).isEmpty();
  }

  @Test
  void late_arriving_cash_resolves_a_pending_batch_with_no_resubmission() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    runner.sweep();
    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);

    TransferId receipt = givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batchId)).containsExactly(receipt);
  }

  @Test
  void a_late_arriving_file_matches_cash_that_was_already_resting_in_collections() {
    // The other direction, and the ordinary one: money arrives first and simply rests in
    // EMPLOYER_COLLECTIONS until a file matches it.
    TransferId receipt = givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batchId)).containsExactly(receipt);
  }

  @Test
  void a_pending_batch_is_not_re_read_from_the_object_store_on_every_sweep() {
    // The header is stored precisely so this does not re-fetch and re-parse a
    // hundred-thousand-row file every few seconds for as long as a wire is outstanding.
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    runner.sweep();

    // Deleting the object entirely: a sweep that still read it would now reject the batch.
    objectStore.breakIt();
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  void a_checksum_that_does_not_match_the_stored_bytes_rejects_the_batch() {
    String content = ContributionFiles.workedExample();
    String objectKey = ContributionFiles.objectKey(organization, "file.csv");
    objectStore.put(objectKey, content);
    String wrongChecksum = ContributionFiles.sha256Hex(content + "tampered");
    UUID batchId = ContributionBatchId.derive(organization, objectKey, wrongChecksum);
    batches.submit(
        new ContributionBatch(
            batchId,
            organization,
            objectKey,
            wrongChecksum,
            ContributionBatchStatus.RECEIVED,
            ContributionFiles.workedExampleDeclaration(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(batches.get(batchId).rejection())
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code()).isEqualTo(BatchRejectionCode.CHECKSUM_MISMATCH));
  }

  @Test
  void a_batch_submitted_for_an_object_nobody_uploaded_is_rejected() {
    String checksum = ContributionFiles.sha256Hex("anything");
    String objectKey = ContributionFiles.objectKey(organization, "never.csv");
    UUID batchId = ContributionBatchId.derive(organization, objectKey, checksum);
    batches.submit(
        new ContributionBatch(
            batchId,
            organization,
            objectKey,
            checksum,
            ContributionBatchStatus.RECEIVED,
            ContributionFiles.workedExampleDeclaration(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.REJECTED);
  }

  @Test
  void a_structurally_wrong_file_is_rejected_and_claims_no_cash() {
    UUID batchId =
        givenSubmitted(
            ContributionFiles.singleRow(750_000L),
            ContributionFiles.declaration(750_000L, 9, 750_000L, 0L, 0L));
    TransferId receipt = givenReceipt(750_000L);

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(batches.get(batchId).rejection())
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH));
    // docs/ENGINEERING-RULES.md §1: never a partial allotment. A rejected batch takes no money with
    // it,
    // even when money matching its declared total is sitting right there.
    assertThat(claims.claimedBy(batchId)).isEmpty();
    assertThat(claims.unclaimed(organization))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(receipt);
  }

  @Test
  void an_unreachable_object_store_leaves_the_batch_claimed_rather_than_rejecting_it() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    objectStore.breakIt();

    int resolved = runner.sweep();

    assertThat(resolved).isZero();
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.VALIDATING);
    assertThat(batches.get(batchId).rejection()).isEmpty();
  }

  @Test
  void a_batch_stranded_by_a_dead_process_is_reclaimed_once_its_lease_goes_stale() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    objectStore.breakIt();
    runner.sweep();
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.VALIDATING);

    objectStore.fixIt();
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    BatchValidationRunner later =
        new BatchValidationRunner(
            batches,
            claims,
            objectStore,
            new ReconciliationGate(claims, ledger, accountIds),
            new ContributionBatchValidator(planVersions),
            new ParticipantEnrolmentService(
                participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
            new ContributionStagingService(
                stagedRows,
                batches,
                properties,
                new TransactionTemplate(new DirectTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            properties,
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
    later.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  void a_batch_still_within_its_lease_is_not_reclaimed_by_a_second_sweep() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    objectStore.breakIt();
    runner.sweep();

    objectStore.fixIt();
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    int resolved = runner.sweep();

    assertThat(resolved).isZero();
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.VALIDATING);
  }

  @Test
  void
      a_receipt_taken_between_the_gate_and_the_claim_leaves_this_batch_pending_and_takes_nothing() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    TransferId receipt = givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    UUID otherBatch = UUID.randomUUID();

    // The exact race the claim table's primary key exists for: the gate lists the receipt,
    // then another batch takes it before this one can claim it. The claim must fail whole
    // rather than half-attaching one batch's money to another.
    CashClaimRepository racing =
        new CashClaimRepository() {
          @Override
          public java.util.List<UnclaimedReceipt> unclaimed(OrganizationId organizationId) {
            java.util.List<UnclaimedReceipt> listed = claims.unclaimed(organizationId);
            claims.givenClaimedBy(receipt, otherBatch);
            return listed;
          }

          @Override
          public void claim(
              OrganizationId organizationId,
              UUID id,
              java.util.List<TransferId> transferIds,
              Instant now) {
            claims.claim(organizationId, id, transferIds, now);
          }

          @Override
          public java.util.List<TransferId> claimedBy(UUID id) {
            return claims.claimedBy(id);
          }
        };
    BatchValidationRunner racingRunner =
        new BatchValidationRunner(
            batches,
            racing,
            objectStore,
            new ReconciliationGate(racing, ledger, accountIds),
            new ContributionBatchValidator(planVersions),
            new ParticipantEnrolmentService(
                participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
            new ContributionStagingService(
                stagedRows,
                batches,
                properties,
                new TransactionTemplate(new DirectTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            properties,
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    racingRunner.sweep();

    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(claims.claimedBy(batchId)).isEmpty();
    assertThat(claims.claimedBy(otherBatch)).containsExactly(receipt);
  }

  @Test
  void two_batches_can_never_consume_the_same_wire() {
    // The acceptance criterion this whole allocation table exists for.
    TransferId receipt = givenReceipt(750_000L);
    UUID first =
        givenSubmitted(
            ContributionFiles.singleRow(750_000L),
            ContributionFiles.singleRowDeclaration(750_000L));
    String secondContent = ContributionFiles.singleRow(750_000L) + "\n";
    String secondKey = ContributionFiles.objectKey(organization, "second.csv");
    objectStore.put(secondKey, secondContent);
    UUID second =
        ContributionBatchId.derive(
            organization, secondKey, ContributionFiles.sha256Hex(secondContent));
    batches.submit(
        new ContributionBatch(
            second,
            organization,
            secondKey,
            ContributionFiles.sha256Hex(secondContent),
            ContributionBatchStatus.RECEIVED,
            ContributionFiles.singleRowDeclaration(750_000L),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    runner.sweep();

    assertThat(claims.claimedBy(first)).containsExactly(receipt);
    assertThat(claims.claimedBy(second)).isEmpty();
    assertThat(batches.get(second).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
  }

  @Test
  void an_operator_assignment_unblocks_a_batch_the_gate_refused_to_guess_at() {
    // End to end for the tie-break: two identical wires, so the gate declines; a human
    // assigns one; the next sweep validates the batch and takes nothing extra.
    UUID batchId =
        givenSubmitted(
            ContributionFiles.singleRow(600_000L),
            ContributionFiles.singleRowDeclaration(600_000L));
    TransferId chosen = givenReceipt(600_000L);
    TransferId other = givenReceipt(600_000L);

    runner.sweep();
    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);

    claims.givenClaimedBy(chosen, batchId);
    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batchId)).containsExactly(chosen);
    assertThat(claims.unclaimed(organization))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(other);
  }

  @Test
  void validating_against_an_assignment_claims_nothing_a_second_time() {
    // The claim rows already exist. Running them through the claim path would violate the
    // allocation table's primary key and turn a successful unblock into a failure.
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    TransferId receipt = givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    runner.sweep();
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);

    // Force it back through the gate the way a reclaim would, with its claim still held.
    batches.resolve(
        new ContributionBatch(
            batchId,
            organization,
            batches.get(batchId).objectKey(),
            batches.get(batchId).checksumSha256(),
            ContributionBatchStatus.PENDING_RECONCILIATION,
            batches.get(batchId).declared(),
            Optional.empty(),
            batches.get(batchId).fileValidatedAt(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batchId)).containsExactly(receipt);
  }

  @Test
  void an_already_resolved_batch_is_never_claimed_again() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    runner.sweep();

    int resolved = runner.sweep();

    assertThat(resolved).isZero();
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  void a_structurally_sound_file_enrols_its_participants_even_before_its_cash_arrives() {
    // Auto-enrolment does not wait on the reconciliation gate: this batch has no cash
    // receipt at all, so it lands on PENDING_RECONCILIATION, and its two participants are
    // enrolled anyway.
    givenSubmitted(ContributionFiles.workedExample());

    runner.sweep();

    assertThat(participants.find(organization, "P-001")).isPresent();
    assertThat(participants.find(organization, "P-002")).isPresent();
    assertThat(participants.size()).isEqualTo(2);
  }

  @Test
  void a_structurally_rejected_batch_enrols_nobody() {
    givenSubmitted(
        ContributionFiles.singleRow(500L), ContributionFiles.declaration(500L, 9, 500L, 0L, 0L));

    runner.sweep();

    assertThat(participants.size()).isZero();
  }

  @Test
  void a_pending_batch_swept_again_enrols_nobody_a_second_time() {
    // A file already read is never re-fetched (fileValidatedAt), and enrolment runs at the
    // same point — so a batch swept many times while its cash is outstanding still enrols
    // each participant exactly once.
    givenSubmitted(ContributionFiles.workedExample());
    runner.sweep();
    assertThat(participants.size()).isEqualTo(2);

    runner.sweep();
    runner.sweep();

    assertThat(participants.size()).isEqualTo(2);
  }

  @Test
  void a_validated_batch_appears_as_staged_with_the_correct_value_date() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(stagedRows.findByBatch(organization, batchId))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.valueDate()).isEqualTo(VALUE_DATE))
        .extracting(StagedRow::participantInternalRef)
        .containsExactlyInAnyOrder("P-001", "P-002");
    assertThat(
            stagedRows.find(
                organization, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED))
        .as("the staged set is queryable by organization, period and status")
        .hasSize(2);
  }

  @Test
  void total_staged_equals_the_batch_s_declared_total() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    runner.sweep();

    long totalStaged =
        stagedRows.findByBatch(organization, batchId).stream()
            .mapToLong(
                row ->
                    row.amountOf(ContributionSource.EMPLOYEE)
                        + row.amountOf(ContributionSource.EMPLOYER)
                        + row.amountOf(ContributionSource.VOLUNTARY))
            .sum();

    assertThat(totalStaged).isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
  }

  @Test
  void rows_captured_for_a_batch_still_pending_reconciliation_do_not_appear_in_the_staged_set() {
    // Staging captures a batch's rows at the same point enrolment does — before the gate —
    // but "the staged set" is answered by joining against the batch's own status, so a row
    // is never visible as staged ahead of that decision.
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(stagedRows.findByBatch(organization, batchId))
        .as("captured, even though its cash has not arrived")
        .hasSize(2);
    assertThat(
            stagedRows.find(
                organization, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED))
        .as("not yet part of the staged set")
        .isEmpty();
  }

  @Test
  void a_structurally_rejected_batch_stages_nothing() {
    UUID batchId =
        givenSubmitted(
            ContributionFiles.singleRow(500L),
            ContributionFiles.declaration(500L, 9, 500L, 0L, 0L));

    runner.sweep();

    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(stagedRows.findByBatch(organization, batchId)).isEmpty();
  }

  @Test
  void a_pending_batch_swept_again_stages_each_row_exactly_once() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    runner.sweep();
    assertThat(stagedRows.findByBatch(organization, batchId)).hasSize(2);

    runner.sweep();
    runner.sweep();

    assertThat(stagedRows.findByBatch(organization, batchId)).hasSize(2);
  }

  @Test
  void
      a_transient_failure_during_reconciliation_persists_file_validated_at_and_reclaims_without_refetching() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());

    // Construct a runner with a gate that throws a RuntimeException during evaluate.
    ReconciliationGate failingGate =
        new ReconciliationGate(claims, ledger, accountIds) {
          @Override
          public Outcome evaluate(
              OrganizationId organizationId, UUID batchId, long declaredTotalCents) {
            throw new RuntimeException("Simulated TigerBeetle connection timeout");
          }
        };

    BatchValidationRunner fragileRunner =
        new BatchValidationRunner(
            batches,
            claims,
            objectStore,
            failingGate,
            new ContributionBatchValidator(planVersions),
            new ParticipantEnrolmentService(
                participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
            new ContributionStagingService(
                stagedRows,
                batches,
                properties,
                new TransactionTemplate(new DirectTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            properties,
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    int resolved = fragileRunner.sweep();

    assertThat(resolved).isZero();
    ContributionBatch batch = batches.get(batchId);
    // Structural validation and staging completed, so fileValidatedAt is durably recorded.
    assertThat(batch.fileValidatedAt()).isPresent();
    // Batch stays in VALIDATING with lease stamped until staleAfter.
    assertThat(batch.status()).isEqualTo(ContributionBatchStatus.VALIDATING);
    assertThat(batch.validationStartedAt()).isPresent();
    assertThat(stagedRows.findByBatch(organization, batchId)).hasSize(2);

    // Break the object store now -- reclaiming this batch after stale lease must NOT re-fetch from
    // the store!
    objectStore.breakIt();
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    // Next sweep after staleAfter (11 minutes later) reclaims the batch and reconciles without
    // reading object store.
    BatchValidationRunner later =
        new BatchValidationRunner(
            batches,
            claims,
            objectStore,
            new ReconciliationGate(claims, ledger, accountIds),
            new ContributionBatchValidator(planVersions),
            new ParticipantEnrolmentService(
                participants, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry()),
            new ContributionStagingService(
                stagedRows,
                batches,
                properties,
                new TransactionTemplate(new DirectTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            properties,
            new TransactionTemplate(new DirectTransactionManager()),
            Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));

    int retried = later.sweep();

    assertThat(retried).isEqualTo(1);
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  void reclaiming_a_file_validated_batch_skips_object_store_fetch_completely() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    runner.sweep();
    assertThat(batches.get(batchId).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(batches.get(batchId).fileValidatedAt()).isPresent();

    // Break the object store -- next sweep should not care since file is already validated.
    objectStore.breakIt();
    givenReceipt(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);

    int resolved = runner.sweep();

    assertThat(resolved).isEqualTo(1);
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  void release_validation_resets_lease_back_to_received_or_pending_reconciliation() {
    UUID batchId = givenSubmitted(ContributionFiles.workedExample());
    runner.sweep(); // reaches PENDING_RECONCILIATION

    // Claim it again
    List<ContributionBatch> claimed =
        batches.claimForValidation(NOW.plusSeconds(10), NOW.minusSeconds(600), 10);
    assertThat(claimed).hasSize(1);
    assertThat(batches.get(batchId).status()).isEqualTo(ContributionBatchStatus.VALIDATING);

    // Release validation
    boolean released = batches.releaseValidation(batchId, NOW.plusSeconds(20));
    assertThat(released).isTrue();
    ContributionBatch batch = batches.get(batchId);
    assertThat(batch.status()).isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(batch.validationStartedAt()).isEmpty();
  }
}
