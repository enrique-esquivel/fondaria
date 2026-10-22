package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.config.BatchValidationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ContributionStagingService} once a file is larger than one linked chain (ADR-0017).
 *
 * <p>The row cap is set to a handful here rather than the production 4,093. What is being proved is
 * the shape of a split — which batch each row lands under, what a part carries, and that re-running
 * converges — none of which depends on the size at which it happens, and a 200,000-row fixture
 * would cost minutes to prove the same thing.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContributionStagingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
  private static final int ROWS_PER_PART = 3;

  private final OrganizationId organization = new OrganizationId(UUID.randomUUID());
  private final InMemoryContributionBatchRepository batches =
      new InMemoryContributionBatchRepository();
  private final InMemoryStagedRowRepository stagedRows = new InMemoryStagedRowRepository(batches);

  private final ContributionStagingService staging =
      new ContributionStagingService(
          stagedRows,
          batches,
          new BatchValidationProperties(
              Duration.ofSeconds(5), 10, Duration.ofMinutes(10), ROWS_PER_PART),
          new TransactionTemplate(new DirectTransactionManager()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void a_file_small_enough_to_allot_in_one_chain_creates_no_parts_at_all() {
    ContributionBatch file = givenSubmittedFile();

    int parts = staging.stage(file, fileOf(3));

    assertThat(parts).isEqualTo(1);
    assertThat(stagedRows.findByBatch(organization, file.batchId())).hasSize(3);
    assertThat(batches.progressOf(organization, file.batchId()))
        .hasValueSatisfying(progress -> assertThat(progress.isSplit()).isFalse());
  }

  @Test
  void a_larger_file_becomes_several_batches_and_the_submitted_one_is_part_one() {
    ContributionBatch file = givenSubmittedFile();

    int parts = staging.stage(file, fileOf(7));

    assertThat(parts).isEqualTo(3);
    // Part 1's rows stay on the batch the employer submitted, so a file that is split is
    // still reachable by the id they were given — and no status has to be invented for a
    // batch that holds nothing.
    assertThat(refsUnder(file.batchId())).containsExactly("P-0", "P-1", "P-2");
    assertThat(refsUnder(partId(file, 2))).containsExactly("P-3", "P-4", "P-5");
    assertThat(refsUnder(partId(file, 3))).containsExactly("P-6");
  }

  @Test
  void a_part_waits_on_its_file_rather_than_being_staged_or_gated_on_its_own() {
    ContributionBatch file = givenSubmittedFile();

    staging.stage(file, fileOf(7));

    ContributionBatch part = batches.get(partId(file, 2));
    assertThat(part.status()).isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    // Not dealable: staged_at is what the cut-off test reads, and a part becomes dealable
    // only when its file's cash reconciles.
    assertThat(part.stagedAt()).isEmpty();
    // The file has been read, so no sweep should ever re-fetch and re-parse it for a part.
    assertThat(part.fileValidatedAt()).isPresent();
    // Excluded from both file-level sweeps, which is what "the employer funds once" rests on.
    assertThat(batches.claimForValidation(NOW, NOW.minusSeconds(600), 50))
        .extracting(ContributionBatch::batchId)
        .doesNotContain(part.batchId());
    assertThat(batches.pendingReconciliation(organization))
        .extracting(ContributionBatch::batchId)
        .doesNotContain(part.batchId());
  }

  @Test
  void a_part_carries_its_own_rows_totals_and_not_the_files_declared_total() {
    ContributionBatch file = givenSubmittedFile();

    staging.stage(file, fileOf(7));

    ContributionBatch lastPart = batches.get(partId(file, 3));
    // One row of 300000 + 150000. The file declared 7 rows' worth; a part declares nothing
    // and no gate ever reads this, but the row has to say what it contains.
    assertThat(lastPart.declaredTotalCents()).isEqualTo(450_000L);
    assertThat(lastPart.declared().declaredRowCount()).isEqualTo(1);
    assertThat(lastPart.declared().declaredCentsFor(ContributionSource.EMPLOYEE))
        .isEqualTo(300_000L);
    assertThat(lastPart.declared().declaredCentsFor(ContributionSource.EMPLOYER))
        .isEqualTo(150_000L);
    assertThat(file.declaredTotalCents()).isEqualTo(7 * 450_000L);
  }

  /**
   * The crash-and-retry case. Staging is re-run whenever a lease goes stale, and a second run that
   * created a second set of parts would stage every participant twice — a doubled contribution.
   */
  @Test
  void staging_the_same_file_twice_converges_on_the_same_parts_and_the_same_rows() {
    ContributionBatch file = givenSubmittedFile();

    staging.stage(file, fileOf(7));
    int rowsAfterFirst = stagedRows.size();
    staging.stage(file, fileOf(7));

    assertThat(stagedRows.size()).isEqualTo(rowsAfterFirst);
    assertThat(batches.progressOf(organization, file.batchId()))
        .hasValueSatisfying(progress -> assertThat(progress.partCount()).isEqualTo(3));
  }

  @Test
  void a_part_and_its_file_report_the_same_progress_read_through_either_id() {
    ContributionBatch file = givenSubmittedFile();
    staging.stage(file, fileOf(7));

    BatchPartProgress fromFile = batches.progressOf(organization, file.batchId()).orElseThrow();
    BatchPartProgress fromPart = batches.progressOf(organization, partId(file, 3)).orElseThrow();

    assertThat(fromFile.fileBatchId()).isEqualTo(file.batchId());
    assertThat(fromPart.fileBatchId()).isEqualTo(file.batchId());
    assertThat(fromFile.partCount()).isEqualTo(3);
    assertThat(fromPart.partCount()).isEqualTo(3);
    assertThat(fromFile.partNumber()).isEqualTo(1);
    assertThat(fromPart.partNumber()).isEqualTo(3);
    assertThat(fromFile.allottedParts()).isZero();
  }

  private static UUID partId(ContributionBatch file, int partNumber) {
    return ContributionBatchId.derivePart(file.batchId(), partNumber);
  }

  private List<String> refsUnder(UUID batchId) {
    return stagedRows.findByBatch(organization, batchId).stream()
        .map(StagedRow::participantInternalRef)
        .toList();
  }

  private ContributionBatch givenSubmittedFile() {
    String objectKey = "contribution-batches/" + organization.value() + "/plan/period/file.csv";
    String checksum = "a".repeat(64);
    ContributionBatch file =
        new ContributionBatch(
            ContributionBatchId.derive(organization, objectKey, checksum),
            organization,
            objectKey,
            checksum,
            ContributionBatchStatus.RECEIVED,
            new BatchDeclaration(
                ContributionFiles.PLAN_ID,
                ContributionFiles.PAYROLL_PERIOD,
                ContributionFiles.VALUE_DATE,
                7 * 450_000L,
                7,
                Map.of(
                    ContributionSource.EMPLOYEE,
                    7 * 300_000L,
                    ContributionSource.EMPLOYER,
                    7 * 150_000L,
                    ContributionSource.VOLUNTARY,
                    0L)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW);
    return batches.submit(file);
  }

  private static ContributionFile fileOf(int rowCount) {
    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    amounts.put(ContributionSource.EMPLOYEE, 300_000L);
    amounts.put(ContributionSource.EMPLOYER, 150_000L);
    amounts.put(ContributionSource.VOLUNTARY, 0L);

    List<ContributionFile.ParticipantRow> rows = new ArrayList<>(rowCount);
    for (int i = 0; i < rowCount; i++) {
      rows.add(
          new ContributionFile.ParticipantRow(
              "P-" + i,
              "EMP-" + i,
              LocalDate.of(2020, 1, 1),
              LocalDate.of(2026, 1, 1),
              Map.copyOf(amounts)));
    }
    return new ContributionFile(rows);
  }

  @Test
  void a_batch_whose_parts_are_already_allotted_is_never_restaged() {
    ContributionBatch file = givenSubmittedFile();
    staging.stage(file, fileOf(7));
    int initialRowCount = stagedRows.findByBatch(organization, file.batchId()).size();
    assertThat(initialRowCount).isEqualTo(3);

    // Simulate part 2 being allotted into the ledger by allotment-job
    UUID part2Id = partId(file, 2);
    batches.markAllotted(part2Id, NOW);

    // Calling stage again on a batch with allotted parts must skip staging
    int parts = staging.stage(file, fileOf(7));
    assertThat(parts).isEqualTo(3);
    assertThat(stagedRows.findByBatch(organization, file.batchId())).hasSize(initialRowCount);
  }
}
