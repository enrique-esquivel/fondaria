package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.allotmentjob.allotment.AllotmentRunner;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.batch.BatchDeclaration;
import dev.fondaria.contribution.batch.BatchValidationRunner;
import dev.fondaria.contribution.batch.ContributionBatch;
import dev.fondaria.contribution.batch.ContributionBatchService;
import dev.fondaria.contribution.batch.ContributionBatchStatus;
import dev.fondaria.contribution.batch.ContributionFileParser;
import dev.fondaria.contribution.batch.ContributionSource;
import dev.fondaria.contribution.cashreceipt.CashReceiptService;
import dev.fondaria.contribution.cashreceipt.RecordCashReceiptCommand;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.rules.DealingDateRule;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One employer, one file, many participants — the shape the platform actually runs at, and the one
 * {@link Scenario} cannot express because it is a record of a single participant's own figures.
 *
 * <p><b>Why a whole file rather than N scenarios.</b> Fifty thousand contributions as fifty
 * thousand scenarios would be fifty thousand employers, fifty thousand wires and fifty thousand
 * one-row files — which exercises registration, not contribution. One employer wiring once and
 * uploading one fifty-thousand-row dispersion file is what a payroll run is, and it is the same
 * path the demo's 200,000-employee beat takes.
 *
 * <p><b>It is deliberately split into parts by the platform, not by this class.</b> A file whose
 * rows would overflow one linked chain is divided by {@code BatchPartitioner} (ADR-0017), and each
 * part allots as its own atomic chain. Nothing here knows how many parts there were: the assertion
 * is that every part of the file reached {@code ALLOTTED}, which is the file's own definition of
 * done.
 *
 * @param participants how many rows the file carried, one per participant
 * @param employeeCentsEach identical per participant, so the expected totals are a multiplication
 *     rather than a sum this class also computes
 */
record BulkContribution(
    OrganizationId organizationId,
    FundId fundId,
    long unitLedgerId,
    int participants,
    LocalDate valueDate,
    LocalDate dealingDate,
    long navScaled,
    long employeeCentsEach,
    long employerCentsEach) {

  private static final String PLAN_ID = "PLAN-E2E-SOAK";
  private static final String PAYROLL_PERIOD = "2026-08-B";

  long totalCents() {
    return (employeeCentsEach + employerCentsEach) * participants;
  }

  /** The scope one participant's holding accounts are derived under, 0-based. */
  String participantRef(int index) {
    return "SOAK-" + String.format("%08d", index);
  }

  /**
   * Runs the whole employer-facing path once for a file of {@code participants} rows: register,
   * receive one wire for the whole file, upload, validate and stage (which splits it into parts),
   * strike the price the file deals at, and allot every part.
   */
  static BulkContribution contribute(
      PlatformStack stack,
      Scenario.TargetFund fund,
      int participants,
      long employeeCentsEach,
      long employerCentsEach,
      long navScaled) {
    PlanVersionRepository planVersions = stack.contributionApiBean(PlanVersionRepository.class);
    CashReceiptService cashReceipts = stack.contributionApiBean(CashReceiptService.class);
    ContributionBatchService batches = stack.contributionApiBean(ContributionBatchService.class);
    BatchValidationRunner validation = stack.contributionApiBean(BatchValidationRunner.class);
    BusinessCalendar calendar = stack.allotmentJobBean(BusinessCalendar.class);
    JdbcClient jdbc = stack.contributionApiBean(JdbcClient.class);

    OrganizationId organizationId = Scenario.registerOrganization(stack);
    planVersions.publish(
        new PlanVersion(
            organizationId,
            PLAN_ID,
            LocalDate.of(2020, 1, 1),
            1,
            1,
            2,
            // Five-year vesting, so employer money lands unvested: the tie-out must sum both of a
            // participant's holding accounts, and a suite whose employer lots vested immediately
            // would pass with only one of them read.
            5,
            0,
            fund.fundId(),
            Instant.parse("2020-01-01T00:00:00Z")));

    LocalDate valueDate = calendar.businessDateOf(Instant.now());
    long totalCents = (employeeCentsEach + employerCentsEach) * (long) participants;

    cashReceipts.record(
        new RecordCashReceiptCommand(
            organizationId,
            "soak-" + UUID.randomUUID(),
            Money.of(totalCents, CurrencyCode.MXN),
            valueDate,
            "SPEI-" + UUID.randomUUID(),
            "ops-e2e"));

    BulkContribution pending =
        new BulkContribution(
            organizationId,
            fund.fundId(),
            fund.unitLedgerId(),
            participants,
            valueDate,
            valueDate,
            navScaled,
            employeeCentsEach,
            employerCentsEach);

    String file = pending.file();
    PresignedUpload slot =
        batches.createUploadSlot(organizationId, PLAN_ID, PAYROLL_PERIOD, "acme-soak.csv");
    assertThat(Scenario.put(slot.url(), file)).as("upload to the presigned slot").isEqualTo(200);

    ContributionBatch batch =
        batches.submit(
            organizationId,
            slot.objectKey(),
            Scenario.sha256Hex(file),
            new BatchDeclaration(
                PLAN_ID,
                PAYROLL_PERIOD,
                valueDate,
                totalCents,
                participants,
                Map.of(
                    ContributionSource.EMPLOYEE, employeeCentsEach * participants,
                    ContributionSource.EMPLOYER, employerCentsEach * participants,
                    ContributionSource.VOLUNTARY, 0L)));

    validation.sweep();
    ContributionBatch staged = batches.get(organizationId, batch.batchId());
    assertThat(staged.status())
        .as("the file validated and staged: %s", staged.rejection())
        .isEqualTo(ContributionBatchStatus.STAGED);

    LocalDate dealingDate =
        DealingDateRule.dealingDateFor(
            staged.declared().valueDate(), staged.stagedAt().orElseThrow(), 0, calendar);
    Scenario.strikeNav(jdbc, fund.fundId(), dealingDate, navScaled);

    stack.allotmentJobBean(AllotmentRunner.class).sweep();
    assertThat(unallottedParts(jdbc, batch.batchId()))
        .as(
            "every part of the file allotted — a part left STAGED means its own chain was refused,"
                + " and the sweep logs and swallows that")
        .isZero();

    return new BulkContribution(
        organizationId,
        fund.fundId(),
        fund.unitLedgerId(),
        participants,
        valueDate,
        dealingDate,
        navScaled,
        employeeCentsEach,
        employerCentsEach);
  }

  /**
   * The dispersion file: one row per participant, every row identical but for its reference, so the
   * expected fund totals are {@code participants ×} one row's figures and not a sum this class also
   * computed.
   */
  private String file() {
    StringBuilder csv = new StringBuilder(participants * 64);
    csv.append(ContributionFileParser.columnHeaderLine()).append('\n');
    for (int i = 0; i < participants; i++) {
      csv.append(participantRef(i))
          .append(",ACME-EMP-")
          .append(String.format("%08d", i))
          .append(",2019-03-01,2026-01-15,")
          .append(employeeCentsEach)
          .append(',')
          .append(employerCentsEach)
          .append(",0\n");
    }
    return csv.toString();
  }

  /** Parts of this file — the submitted row is part 1 (V24) — that the allotment did not take. */
  private static long unallottedParts(JdbcClient jdbc, UUID fileBatchId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM workflow.contribution_batch
             WHERE (batch_id = :batchId OR parent_batch_id = :batchId)
               AND status <> 'ALLOTTED'
            """)
        .param("batchId", fileBatchId)
        .query(Long.class)
        .single();
  }
}
