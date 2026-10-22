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
import dev.fondaria.contribution.config.BatchValidationProperties;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Task 3.7 — the phase's own integration tests, distinct from the per-step suites Tasks 3.3, 3.4
 * and 3.6 each already carry ({@code BACKLOG.md}: "each step carries its own tests").
 *
 * <p>What this class adds that no earlier one does: the full pipeline exercised as one path rather
 * than one control-flow branch at a time, a batch large enough to say something about performance,
 * and many batches settling together — the three questions "does it work end to end", "does it
 * still work at scale" and "does it still work as a whole", none of which a two-row worked example
 * can answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Phase3IntegrationTest extends ServiceIntegrationTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(Phase3IntegrationTest.class);
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 20);

  @Autowired private ContributionBatchService batches;
  @Autowired private CashClaimRepository claims;
  @Autowired private BatchValidationRunner runner;
  @Autowired private CashReceiptService cashReceipts;
  @Autowired private OrganizationService organizations;
  @Autowired private ParticipantRepository participants;
  @Autowired private StagedRowRepository stagedRows;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private PlanVersionRepository planVersions;
  @Autowired private FundRepository funds;
  @Autowired private BatchValidationProperties validationProperties;

  @RepeatedTest(10)
  @DisplayName(
      "the full path — cash received, file received, matched, validated, staged — holds"
          + " identically across ten consecutive runs")
  void theFullPathIsDeterministicAcrossTenRuns() throws Exception {
    OrganizationId organizationId = registerOrganization();

    TransferId receipt =
        postCashReceipt(organizationId, ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
    ContributionBatch batch = submit(organizationId, ContributionFiles.workedExample());
    assertThat(batches.get(organizationId, batch.batchId()).status())
        .as("file received")
        .isEqualTo(ContributionBatchStatus.RECEIVED);

    runner.sweep();

    ContributionBatch resolved = batches.get(organizationId, batch.batchId());
    assertThat(resolved.status())
        .as("validated and staged")
        .isEqualTo(ContributionBatchStatus.STAGED);
    assertThat(claims.claimedBy(batch.batchId())).as("matched").containsExactly(receipt);
    assertThat(participants.find(organizationId, "P-001")).isPresent();
    assertThat(participants.find(organizationId, "P-002")).isPresent();
    assertThat(stagedRows.findByBatch(organizationId, batch.batchId())).hasSize(2);
    assertThat(collectionsBalance(organizationId))
        .isEqualTo(ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS);
  }

  @Test
  @DisplayName("a 10,000-row batch validates comfortably inside the validation lease window")
  void a10000RowBatchValidatesWithinTheLeaseWindow() throws Exception {
    int rowCount = 10_000;
    OrganizationId organizationId = registerOrganization();
    postCashReceipt(
        organizationId, ContributionFiles.manyRowsDeclaration(rowCount).declaredTotalCents());
    ContributionBatch batch =
        submit(
            organizationId,
            ContributionFiles.manyRows(rowCount),
            ContributionFiles.manyRowsDeclaration(rowCount));

    Instant started = Instant.now();
    runner.sweep();
    Duration elapsed = Duration.between(started, Instant.now());
    LOGGER.info("Task 3.7: a {}-row batch validated in {}", rowCount, elapsed);

    assertThat(batches.get(organizationId, batch.batchId()).status())
        .isEqualTo(ContributionBatchStatus.STAGED);
    // 10,000 rows is more than one linked chain can allot, so this file is three batches
    // (ADR-0017) and its rows are counted across them rather than under the submitted id. Every
    // row is still staged exactly once — a row lost between parts is a participant who is never
    // priced, and a row in two parts is a participant priced twice.
    BatchPartProgress progress = batches.progressOf(organizationId, batch.batchId()).orElseThrow();
    assertThat(progress.partCount()).isEqualTo(3);
    List<StagedRow> staged =
        new ArrayList<>(stagedRows.findByBatch(organizationId, batch.batchId()));
    for (int partNumber = 2; partNumber <= progress.partCount(); partNumber++) {
      staged.addAll(
          stagedRows.findByBatch(
              organizationId, ContributionBatchId.derivePart(batch.batchId(), partNumber)));
    }
    assertThat(staged).hasSize(rowCount);
    assertThat(staged).extracting(StagedRow::participantInternalRef).doesNotHaveDuplicates();
    // BatchValidationProperties' own javadoc: staleAfter "must comfortably exceed the time to
    // fetch, parse and check the largest file expected, or a slow validation would be reclaimed
    // underneath itself." Half of the configured lease is that "comfortably" bar, made concrete
    // — grounded in the production contract this class already depends on, not an invented number.
    assertThat(elapsed)
        .as("must fit well inside the validation lease, or a real sweep would reclaim its own work")
        .isLessThan(validationProperties.staleAfter().dividedBy(2));
  }

  @Test
  @DisplayName("soak: 100 batches settle with cash and staged totals in agreement")
  void soakOneHundredBatchesSettleConsistently() throws Exception {
    int batchCount = 100;
    OrganizationId organizationId = registerOrganization();
    List<ContributionBatch> submitted = new ArrayList<>();
    long totalDeclaredCents = 0L;

    for (int i = 0; i < batchCount; i++) {
      int rowCount = 5 + i; // distinct totals, so no two outstanding receipts ever tie
      BatchDeclaration declared = ContributionFiles.manyRowsDeclaration(rowCount);
      postCashReceipt(organizationId, declared.declaredTotalCents());
      submitted.add(submit(organizationId, ContributionFiles.manyRows(rowCount), declared));
      totalDeclaredCents += declared.declaredTotalCents();
    }

    runner.sweep();

    long totalStagedCents = 0L;
    for (ContributionBatch batch : submitted) {
      assertThat(batches.get(organizationId, batch.batchId()).status())
          .as("batch %s", batch.batchId())
          .isEqualTo(ContributionBatchStatus.STAGED);
      for (StagedRow row : stagedRows.findByBatch(organizationId, batch.batchId())) {
        totalStagedCents +=
            row.amountOf(ContributionSource.EMPLOYEE)
                + row.amountOf(ContributionSource.EMPLOYER)
                + row.amountOf(ContributionSource.VOLUNTARY);
      }
    }

    assertThat(totalStagedCents)
        .as("what every batch staged sums to what every batch declared, and to the ledger balance")
        .isEqualTo(totalDeclaredCents)
        .isEqualTo(collectionsBalance(organizationId));
  }

  private long collectionsBalance(OrganizationId organizationId) {
    AccountId account =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    return ledger
        .lookupAccount(account)
        .map(found -> found instanceof LedgerAccount.Cash cash ? cash.balance().cents() : 0L)
        .orElse(0L);
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
   * Same convention {@code ContributionBatchIntegrationTest} uses: one fresh organization per test.
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
