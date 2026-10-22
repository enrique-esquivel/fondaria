package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.cashreceipt.CashReceiptService;
import dev.fondaria.contribution.cashreceipt.RecordCashReceiptCommand;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.participant.Participant;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Task 3.4's acceptance criteria, end to end: real PostgreSQL, real object store, real TigerBeetle.
 *
 * <h2>Why this suite exists when the unit tests already cover the logic</h2>
 *
 * <p>Doubles prove control flow; containers prove money. Every claim below about a <b>balance</b> —
 * how much is in {@code EMPLOYER_COLLECTIONS}, whether a movement happened once — is read from
 * TigerBeetle through {@link LedgerPort}, because a double that agreed with the implementation
 * would prove only that the implementation agrees with itself.
 *
 * <h2>What is asserted, and what cannot be yet</h2>
 *
 * <p>The phase doc's last criterion is "{@code employer_collections} nets to zero once every batch
 * is allotted". <b>Allotment does not exist.</b> It is {@code CONTRIBUTION_ALLOTMENT} ({@code
 * EMPLOYER_COLLECTIONS → ALLOTMENT_SUSPENSE → FUND_CASH}), fired at a NAV strike, and the phase
 * plan puts it in Phase 21. So the reachable half is asserted here — after reconciliation, every
 * cent that arrived for a freshly-derived organization is accounted for by exactly one batch, and
 * no cent is claimed twice — and the netting to zero is left to the task that posts the movement
 * that drains the account. Posting a drain transfer from a test, purely to make the assertion pass,
 * would prove TigerBeetle's arithmetic rather than this service's behaviour.
 *
 * <p>Every test registers an organization nobody has used before. Fixtures are reused across runs
 * and TigerBeetle cannot be reset at all (docs/ENGINEERING-RULES.md §4), so a shared employer would
 * have a balance assertion pass or fail on what an earlier run left in its account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContributionBatchIntegrationTest extends ServiceIntegrationTest {

  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 20);

  @Autowired private ContributionBatchService batches;
  @Autowired private CashClaimRepository claims;
  @Autowired private BatchValidationRunner runner;
  @Autowired private ReconciliationAgeingService ageing;
  @Autowired private CashReceiptService cashReceipts;
  @Autowired private OrganizationService organizations;
  @Autowired private ParticipantRepository participants;
  @Autowired private StagedRowRepository stagedRows;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private PlanVersionRepository planVersions;
  @Autowired private FundRepository funds;

  @Test
  @DisplayName("a validated batch enrols its participants, carrying both employment dates")
  void aValidatedBatchEnrolsItsParticipants() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    Participant longService = participants.find(organizationId, "P-001").orElseThrow();
    assertThat(longService.payrollRef()).isEqualTo("ACME-EMP-0142");
    assertThat(longService.planId()).isEqualTo(ContributionFiles.PLAN_ID);
    assertThat(longService.employmentStartDate())
        .isEqualTo(ContributionFiles.LONG_SERVICE_EMPLOYMENT_START);
    assertThat(longService.enrolmentDate()).isEqualTo(ContributionFiles.LONG_SERVICE_ENROLMENT);
    assertThat(participants.find(organizationId, "P-002")).isPresent();
  }

  @Test
  @DisplayName("re-sweeping a validated batch enrols nobody a second time")
  void reSweepingEnrolsNobodyTwice() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    submit(organizationId, ContributionFiles.workedExample());
    runner.sweep();
    Participant firstRead = participants.find(organizationId, "P-001").orElseThrow();

    runner.sweep();
    runner.sweep();

    Participant secondRead = participants.find(organizationId, "P-001").orElseThrow();
    assertThat(secondRead.createdAt()).isEqualTo(firstRead.createdAt());
  }

  @Test
  @DisplayName("a structurally rejected batch enrols nobody")
  void aRejectedBatchEnrolsNobody() throws Exception {
    OrganizationId organizationId = registerOrganization();
    submit(
        organizationId,
        ContributionFiles.singleRow(500L),
        ContributionFiles.declaration(500L, 9, 500L, 0L, 0L));

    runner.sweep();

    assertThat(participants.find(organizationId, "P-001")).isEmpty();
  }

  @Test
  @DisplayName("resubmitting the identical file returns the original batch, never a second one")
  void resubmissionIsTheSameBatch() throws Exception {
    OrganizationId organizationId = registerOrganization();
    Uploaded file = upload(organizationId, ContributionFiles.workedExample());

    BatchDeclaration declared = ContributionFiles.workedExampleDeclaration();
    ContributionBatch first =
        batches.submit(organizationId, file.objectKey(), file.checksum(), declared);
    ContributionBatch again =
        batches.submit(organizationId, file.objectKey(), file.checksum(), declared);

    assertThat(again.batchId()).isEqualTo(first.batchId());
    assertThat(again.submittedAt()).isEqualTo(first.submittedAt());
  }

  @Test
  @DisplayName("a file total that does not equal the cash received blocks allotment")
  void aMismatchBlocksAllotment() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, 740_000L);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status()).isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(resolved.rejection()).as("the file is fine; the money is not").isEmpty();
    assertThat(claims.claimedBy(batch.batchId())).isEmpty();
    // The money is untouched and still in the ledger: a blocked batch never sends cash back.
    assertThat(collectionsBalance(organizationId)).isEqualTo(740_000L);
  }

  @Test
  @DisplayName("a fully funded batch is validated and its cash is exactly accounted for")
  void aFundedBatchIsValidated() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId receipt =
        postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batch.batchId())).containsExactly(receipt);
    assertThat(collectionsBalance(organizationId))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    assertThat(claimedTotalFromLedger(batch.batchId()))
        .as("every cent this batch holds is accounted for by exactly one receipt")
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
  }

  @Test
  @DisplayName("a validated batch appears as staged with the correct value date")
  void aValidatedBatchAppearsAsStagedWithTheCorrectValueDate() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(stagedRows.findByBatch(organizationId, batch.batchId()))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.valueDate()).isEqualTo(VALUE_DATE))
        .extracting(StagedRow::participantInternalRef)
        .containsExactlyInAnyOrder("P-001", "P-002");
    assertThat(
            stagedRows.find(
                organizationId, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED))
        .as("the staged set is queryable by organization, period and status")
        .hasSize(2);
  }

  @Test
  @DisplayName("total staged equals total cash in employer_collections for that batch")
  void totalStagedEqualsCashClaimedForTheBatch() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    long totalStaged =
        stagedRows.findByBatch(organizationId, batch.batchId()).stream()
            .mapToLong(
                row ->
                    row.amountOf(ContributionSource.EMPLOYEE)
                        + row.amountOf(ContributionSource.EMPLOYER)
                        + row.amountOf(ContributionSource.VOLUNTARY))
            .sum();

    assertThat(totalStaged)
        .as("read from the batch's own rows, and matching the ledger's own account balance")
        .isEqualTo(claimedTotalFromLedger(batch.batchId()))
        .isEqualTo(collectionsBalance(organizationId));
  }

  @Test
  @DisplayName("a batch pending reconciliation stages nothing visible yet")
  void aPendingBatchIsNotYetInTheStagedSet() throws Exception {
    OrganizationId organizationId = registerOrganization();
    submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(
            stagedRows.find(
                organizationId, ContributionFiles.PAYROLL_PERIOD, ContributionBatchStatus.STAGED))
        .isEmpty();
  }

  @Test
  @DisplayName("a late-arriving file matches previously received cash and proceeds")
  void aLateFileMatchesRestingCash() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId receipt =
        postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    // A receipt with no matching file simply rests in EMPLOYER_COLLECTIONS (Task 3.3).
    assertThat(collectionsBalance(organizationId))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    assertThat(ageing.forOrganization(organizationId).unmatchedCash())
        .extracting(ReconciliationAgeingService.UnmatchedCash::transferId)
        .containsExactly(TransferIdDeriver.toUuid(receipt));

    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());
    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(ageing.forOrganization(organizationId).unmatchedCash()).isEmpty();
  }

  @Test
  @DisplayName("late-arriving cash resolves a blocked batch with no resubmission")
  void lateCashResolvesABlockedBatch() throws Exception {
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());
    runner.sweep();
    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);

    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
  }

  @Test
  @DisplayName("several wires together fund one file, and every one of them is claimed")
  void severalWiresFundOneFile() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId first = postCashReceipt(organizationId, 400_000L);
    TransferId second = postCashReceipt(organizationId, 350_000L);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batch.batchId())).containsExactlyInAnyOrder(first, second);
    assertThat(claimedTotalFromLedger(batch.batchId()))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    assertThat(collectionsBalance(organizationId))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
  }

  @Test
  @DisplayName("two batches never consume the same wire, even when both declare its amount")
  void oneWireFundsAtMostOneBatch() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId receipt = postCashReceipt(organizationId, 750_000L);
    ContributionBatch first =
        submit(
            organizationId,
            ContributionFiles.singleRow(750_000L),
            ContributionFiles.singleRowDeclaration(750_000L));
    ContributionBatch second =
        submit(
            organizationId,
            ContributionFiles.singleRow(750_000L) + "\n",
            ContributionFiles.singleRowDeclaration(750_000L));

    runner.sweep();

    List<UUID> validated =
        List.of(first.batchId(), second.batchId()).stream()
            .filter(
                id -> batches.get(organizationId, id).status() == ContributionBatchStatus.STAGED)
            .toList();
    assertThat(validated).as("exactly one of the two is funded").hasSize(1);
    assertThat(claims.claimedBy(validated.get(0))).containsExactly(receipt);
    // And the cent total claimed across both batches is the money that actually arrived —
    // never twice it, which is what claiming one wire for two batches would mean.
    assertThat(claimedTotalFromLedger(first.batchId()) + claimedTotalFromLedger(second.batchId()))
        .isEqualTo(collectionsBalance(organizationId));
  }

  @Test
  @DisplayName("unmatched cash and unmatched file both appear on the ageing report")
  void bothHalvesAppearOnTheAgeingReport() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId orphanCash = postCashReceipt(organizationId, 100_000L);
    ContributionBatch orphanFile = submit(organizationId, ContributionFiles.workedExample());
    runner.sweep();

    ReconciliationAgeingService.AgeingReport report = ageing.forOrganization(organizationId);

    assertThat(report.unmatchedCash())
        .singleElement()
        .satisfies(
            cash -> {
              assertThat(cash.transferId()).isEqualTo(TransferIdDeriver.toUuid(orphanCash));
              assertThat(cash.amountCents())
                  .as("read from the ledger, never from a database column")
                  .hasValue(100_000L);
              assertThat(cash.valueDate()).isEqualTo(VALUE_DATE);
            });
    assertThat(report.unmatchedFiles())
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.batchId()).isEqualTo(orphanFile.batchId());
              assertThat(file.declaredTotalCents())
                  .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
              assertThat(file.payrollPeriod()).isEqualTo("2026-08-B");
            });
  }

  @Test
  @DisplayName("a structurally wrong file is rejected and takes no money with it")
  void aRejectedBatchClaimsNothing() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, 750_000L);
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.singleRow(750_000L),
            ContributionFiles.declaration(750_000L, 9, 750_000L, 0L, 0L));

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(resolved.rejection())
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH));
    assertThat(claims.claimedBy(batch.batchId())).isEmpty();
    // docs/ENGINEERING-RULES.md §1: never a partial allotment. The money is untouched.
    assertThat(collectionsBalance(organizationId)).isEqualTo(750_000L);
  }

  @Test
  @DisplayName("a source total that does not tie out is rejected, and names only the source")
  void aSourceTotalMismatchIsPersistedAndReadBack() throws Exception {
    // Through real PostgreSQL because the rejection is STORED: the code has to satisfy
    // workflow.contribution_batch's CHECK constraint and the message has to survive a
    // round trip without ever having carried a participant reference into the database.
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, 750_000L);
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.columnHeader()
                + """

                P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0
                P-002,ACME-EMP-0891,2026-02-01,2026-02-01,200000,99900,0
                """,
            ContributionFiles.workedExampleDeclaration());

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(resolved.rejection())
        .hasValueSatisfying(
            rejection -> {
              assertThat(rejection.code()).isEqualTo(BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH);
              assertThat(rejection.message())
                  .as(
                      "docs/ENGINEERING-RULES.md §7: a stored rejection reaches every log downstream of it")
                  .contains("EMPLOYER")
                  .doesNotContain("P-002")
                  .doesNotContain("ACME-EMP-0891");
            });
    // docs/ENGINEERING-RULES.md §1: never a partial allotment. The money is untouched.
    assertThat(claims.claimedBy(batch.batchId())).isEmpty();
    assertThat(collectionsBalance(organizationId)).isEqualTo(750_000L);
  }

  @Test
  @DisplayName("a submitted declaration survives PostgreSQL, per-source totals included")
  void theDeclarationRoundTripsThroughPostgres() throws Exception {
    // The reason this is an integration test: the per-source totals are new columns
    // (V5), and the async validator reads them from this row rather than from the request
    // that carried them. Unstored, SOURCE_SUBTOTAL_MISMATCH could not be evaluated at all
    // — a failure no in-memory repository can show.
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status()).isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(resolved.declared()).isEqualTo(ContributionFiles.workedExampleDeclaration());
    assertThat(resolved.declared().declaredRowCount())
        .as("one row per participant, and the worked example has two")
        .isEqualTo(2);
    assertThat(resolved.declared().declaredCentsFor(ContributionSource.EMPLOYER))
        .isEqualTo(250_000L);
  }

  @Test
  @DisplayName("a rejected batch still reports the plan and payroll period that were refused")
  void aRejectedBatchStillReportsItsPeriod() throws Exception {
    // The known gap this change closes. Under v1 these were null on a REJECTED batch,
    // because they only existed once the file had parsed — so an employer polling the
    // status could not see which payroll period had been refused.
    OrganizationId organizationId = registerOrganization();
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.singleRow(500L),
            ContributionFiles.declaration(500L, 9, 500L, 0L, 0L));

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status()).isEqualTo(ContributionBatchStatus.REJECTED);
    assertThat(resolved.rejection())
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH));
    assertThat(resolved.declared().planId()).isEqualTo(ContributionFiles.PLAN_ID);
    assertThat(resolved.declared().payrollPeriod()).isEqualTo(ContributionFiles.PAYROLL_PERIOD);
    assertThat(resolved.declared().valueDate()).isEqualTo(VALUE_DATE);
  }

  @Test
  @DisplayName("a checksum that does not match the uploaded bytes rejects the batch")
  void aTamperedFileIsRejected() throws Exception {
    OrganizationId organizationId = registerOrganization();
    Uploaded file = upload(organizationId, ContributionFiles.workedExample());
    String wrongChecksum = ContributionFiles.sha256Hex("something else entirely");
    ContributionBatch batch =
        batches.submit(
            organizationId,
            file.objectKey(),
            wrongChecksum,
            ContributionFiles.workedExampleDeclaration());

    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).rejection())
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code()).isEqualTo(BatchRejectionCode.CHECKSUM_MISMATCH));
  }

  @Test
  @DisplayName(
      "two employers with differently configured plans get different outcomes from the same file"
          + " shape")
  void plansAreConfigurablePerOrganization() throws Exception {
    // The configurability proof step 5 exists for (BACKLOG.md): the same worked-example file
    // shape is accepted for one employer and rejected for another, purely because their plans
    // declare different employer matching ratios. Nothing about the file changes.
    OrganizationId dollarForDollarEmployer = registerOrganization();
    // A stricter correction on top of the default 1:2 plan registerOrganization() already
    // published: version 2, same effective date, requiring the employer to match the employee
    // exactly (1:1) rather than half of it.
    planVersions.publish(
        new PlanVersion(
            dollarForDollarEmployer,
            ContributionFiles.PLAN_ID,
            LocalDate.of(2020, 1, 1),
            2,
            1,
            1,
            5,
            0,
            registerFund(),
            java.time.Instant.now()));
    OrganizationId halfMatchEmployer = registerOrganization();

    ContributionBatch rejected = submit(dollarForDollarEmployer, ContributionFiles.workedExample());
    ContributionBatch accepted = submit(halfMatchEmployer, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(dollarForDollarEmployer, rejected.batchId()).rejection())
        .as("150,000 is half of 300,000, not all of it: this employer's 1:1 plan refuses it")
        .hasValueSatisfying(
            rejection ->
                assertThat(rejection.code())
                    .isEqualTo(BatchRejectionCode.EMPLOYER_MATCH_RATIO_BREACH));
    assertThat(batches.get(halfMatchEmployer, accepted.batchId()).status())
        .as("the identical file shape is fine under this employer's 1:2 plan")
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
  }

  @Test
  @DisplayName("one organization's cash can never fund another's batch")
  void cashIsNeverBorrowedAcrossTenants() throws Exception {
    OrganizationId mine = registerOrganization();
    OrganizationId theirs = registerOrganization();
    postCashReceipt(theirs, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(mine, ContributionFiles.workedExample());

    runner.sweep();

    assertThat(batches.get(mine, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    assertThat(collectionsBalance(mine)).isZero();
  }

  @Test
  @DisplayName("a sweep over an already-validated batch changes nothing and claims nothing twice")
  void aValidatedBatchIsNeverReprocessed() throws Exception {
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());
    runner.sweep();
    List<TransferId> claimed = claims.claimedBy(batch.batchId());

    runner.sweep();
    runner.sweep();

    assertThat(claims.claimedBy(batch.batchId())).isEqualTo(claimed);
    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(collectionsBalance(organizationId))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
  }

  /**
   * {@code EMPLOYER_COLLECTIONS[org]} straight from TigerBeetle, never summed from PostgreSQL
   * (ADR-0008 invariant 1). {@code CASH_RECEIPT} credits this account, and the adapter already
   * reports a cash balance as credits minus debits — so this is money held, and it is positive. The
   * organization was derived fresh, so nothing an earlier run left is in it.
   */
  @Test
  @DisplayName("an operator assignment unblocks a batch the gate refused to guess at")
  void anOperatorAssignmentUnblocksAnAmbiguousBatch() throws Exception {
    // Two wires for the same amount, so rules 1 and 2 both decline: a human has to say
    // which is which. This is the runbook path (docs/RUNBOOKS.md#reconciliation-tie-break)
    // exercised against the real claim table, its foreign key and its primary key.
    OrganizationId organizationId = registerOrganization();
    TransferId chosen = postCashReceipt(organizationId, 600_000L);
    TransferId other = postCashReceipt(organizationId, 600_000L);
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.singleRow(600_000L),
            ContributionFiles.singleRowDeclaration(600_000L));

    runner.sweep();
    assertThat(batches.get(organizationId, batch.batchId()).status())
        .as("ambiguous, so the gate declines rather than choosing whose money this is")
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);

    // What the runbook has an operator do, and nothing else: no status is set by hand.
    claims.claim(organizationId, batch.batchId(), List.of(chosen), java.time.Instant.now());
    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batch.batchId())).containsExactly(chosen);
    assertThat(claimedTotalFromLedger(batch.batchId()))
        .as("validated against the ledger's amount for the assigned wire, not a stored one")
        .isEqualTo(600_000L);
    assertThat(claims.unclaimed(organizationId))
        .as("the wire nobody assigned is untouched and still visible for the next file")
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(other);
    assertThat(collectionsBalance(organizationId))
        .as("both wires are still in the ledger; assignment allocates, it never moves money")
        .isEqualTo(1_200_000L);
  }

  @Test
  @DisplayName("an assignment short of the declared total leaves the batch blocked")
  void aPartialAssignmentDoesNotUnblockAndIsNeverToppedUp() throws Exception {
    OrganizationId organizationId = registerOrganization();
    TransferId partial = postCashReceipt(organizationId, 400_000L);
    TransferId untouched = postCashReceipt(organizationId, 350_000L);
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.singleRow(750_000L),
            ContributionFiles.singleRowDeclaration(750_000L));

    claims.claim(organizationId, batch.batchId(), List.of(partial), java.time.Instant.now());
    runner.sweep();

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.PENDING_RECONCILIATION);
    // 400,000 assigned plus 350,000 outstanding equals the declared total, and the gate
    // must NOT take the second: mixing a human's partial decision with automatic matching
    // would consume a wire nobody chose.
    assertThat(claims.claimedBy(batch.batchId())).containsExactly(partial);
    assertThat(claims.unclaimed(organizationId))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(untouched);
  }

  private long collectionsBalance(OrganizationId organizationId) {
    AccountId account =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    return ledger
        .lookupAccount(account)
        .map(found -> found instanceof LedgerAccount.Cash cash ? cash.balance().cents() : 0L)
        .orElse(0L);
  }

  /** What a batch's claimed receipts actually moved, read from the ledger and nowhere else. */
  private long claimedTotalFromLedger(UUID batchId) {
    long total = 0L;
    for (TransferId transferId : claims.claimedBy(batchId)) {
      total +=
          ledger
              .lookupTransfer(transferId)
              .filter(LedgerTransfer.Cash.class::isInstance)
              .map(transfer -> ((LedgerTransfer.Cash) transfer).amount().cents())
              .orElse(0L);
    }
    return total;
  }

  private TransferId postCashReceipt(OrganizationId organizationId, long cents) {
    return cashReceipts
        .record(
            new RecordCashReceiptCommand(
                organizationId,
                "key-" + UUID.randomUUID(),
                Money.of(cents, CurrencyCode.MXN),
                VALUE_DATE,
                "SPEI-" + UUID.randomUUID(),
                "ops-marta"))
        .receipt()
        .transferId();
  }

  private ContributionBatch submit(OrganizationId organizationId, String content) throws Exception {
    return submit(organizationId, content, ContributionFiles.workedExampleDeclaration());
  }

  private ContributionBatch submit(
      OrganizationId organizationId, String content, BatchDeclaration declared) throws Exception {
    Uploaded file = upload(organizationId, content);
    return batches.submit(organizationId, file.objectKey(), file.checksum(), declared);
  }

  /** Uploads through a real presigned URL, so the transport ADR-0009 specifies is exercised. */
  private Uploaded upload(OrganizationId organizationId, String content) throws Exception {
    PresignedUpload slot =
        batches.createUploadSlot(
            organizationId,
            ContributionFiles.PLAN_ID,
            ContributionFiles.PAYROLL_PERIOD,
            "acme-2026-08-b.csv");
    assertThat(put(slot.url(), content)).isEqualTo(200);
    return new Uploaded(slot.objectKey(), ContributionFiles.sha256Hex(content));
  }

  private record Uploaded(String objectKey, String checksum) {}

  /**
   * Every submitted batch now needs a resolvable plan version (step 5, BACKLOG.md), so this
   * registers the organization and publishes {@link ContributionFiles#defaultPlanVersion} for it in
   * the same call — the ratio {@link ContributionFiles#workedExample()} and {@link
   * ContributionFiles#singleRow(long)} both already satisfy.
   */
  private OrganizationId registerOrganization() {
    OrganizationId organizationId =
        organizations
            .register(
                new RegisterOrganizationCommand(
                    FreshTaxIdentifiers.fresh(),
                    "Acme S.A. de C.V.",
                    Optional.empty(),
                    Optional.empty()))
            .organization()
            .organizationId();
    planVersions.publish(ContributionFiles.defaultPlanVersion(organizationId, registerFund()));
    return organizationId;
  }

  /**
   * ADR-0012: a plan version's {@code fund_id} is a foreign key against {@code
   * reference.fund_identity}, so every plan version this suite publishes needs a real fund row
   * behind it. One fresh fund per organization is enough — nothing here asserts fund identity.
   */
  private FundId registerFund() {
    FundId fundId = FundId.of(UUID.randomUUID());
    funds.publish(
        new Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Caja de Ahorro Acme",
            "CAJA_DE_AHORRO",
            CurrencyCode.MXN,
            ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2),
            Units.SCALE,
            LocalDate.of(2020, 1, 1),
            Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    return fundId;
  }

  private static int put(URI url, String content) throws IOException, InterruptedException {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      return http.send(
              HttpRequest.newBuilder(url)
                  .timeout(Duration.ofSeconds(10))
                  .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }
}
