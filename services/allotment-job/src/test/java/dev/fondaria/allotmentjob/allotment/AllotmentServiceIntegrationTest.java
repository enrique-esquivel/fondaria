package dev.fondaria.allotmentjob.allotment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.allotmentjob.batch.ContributionSource;
import dev.fondaria.allotmentjob.batch.StagedBatch;
import dev.fondaria.allotmentjob.batch.StagedRow;
import dev.fondaria.allotmentjob.support.ServiceIntegrationTest;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 21.2's own acceptance criteria, against a real PostgreSQL and TigerBeetle. Every fixture is
 * seeded directly by SQL and {@link LedgerPort}, because the tables and accounts this service reads
 * are written by contribution-api and valuation-api in production — this suite stands in for both,
 * the same way {@code NavStrikeServiceTest} et al. seed their own prerequisites.
 *
 * <p>Reuses chart-of-accounts.md §6's worked example numbers ({@link
 * AllotmentServiceTest#a_batch_allots_to_the_cent_and_to_the_unit_matching_the_worked_example}
 * proves the pricing logic itself against fakes); this suite proves the same chain actually lands
 * on a real ledger and reconciles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AllotmentServiceIntegrationTest extends ServiceIntegrationTest {

  private static final long NAV_SCALED = 10 * Units.SCALE; // 10.00000000
  private static final long EMPLOYEE_CENTS = 300_000L; // P-001, MXN 3,000.00
  private static final long EMPLOYER_CENTS = 150_000L; // P-001, MXN 1,500.00
  private static final long TOTAL_CENTS = EMPLOYEE_CENTS + EMPLOYER_CENTS;

  @Autowired private AllotmentRunner runner;
  @Autowired private AllotmentService allotmentService;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private TransferIdDeriver transferIds;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName(
      "a batch allots to the cent and the unit, EMPLOYER_COLLECTIONS and ALLOTMENT_SUSPENSE net"
          + " to zero, and UNITS_IN_ISSUE matches what participants hold")
  void theFullPathAllotsAndReconciles() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    LocalDate valueDate = LocalDate.of(2026, 8, 19); // a Wednesday
    Instant stagedAt = Instant.parse("2026-08-19T09:00:00Z");
    String planId = "PLAN-ACME-CAJA-1";
    UUID batchId = UUID.randomUUID();

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId, LocalDate.of(2020, 1, 1));
    publishPlanVersion(organizationId, planId, fundId, 5); // scheduled vesting
    long navVersionId = strikeNav(fundId, valueDate, NAV_SCALED);
    stageBatch(organizationId, batchId, planId, valueDate, stagedAt);
    stageRow(organizationId, batchId, "P-001", EMPLOYEE_CENTS, EMPLOYER_CENTS);
    // Simulates the money already having arrived (Task 3.3's CASH_RECEIPT), the state a
    // batch is always in by the time it reaches STAGED.
    fundEmployerCollections(organizationId, TOTAL_CENTS);

    // Not asserted on `allotted`'s own count: fixtures are reused across local runs
    // (docs/ENGINEERING-RULES.md §4), so a sweep this wide (fondaria.allotment.batch-size is set to
    // cover the
    // whole backlog, matching ServiceIntegrationTest's own rationale) can pick up STAGED
    // batches other test runs left behind. Every assertion below is scoped to this test's own
    // batchId instead.
    runner.sweep();

    assertThat(batchStatus(batchId)).isEqualTo("ALLOTTED");

    // EMPLOYER_COLLECTIONS[org]: credited TOTAL_CENTS by the receipt above, debited TOTAL_CENTS
    // by allotment -- Task 3.4's deliberately unasserted criterion, finally reachable.
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    assertThat(cashBalance(employerCollections).cents()).isZero();

    AccountId allotmentSuspense =
        accountIds.derive(LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE, fundId.toString());
    assertThat(cashBalance(allotmentSuspense).cents()).isZero();

    AccountId fundCash = accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fundId.toString());
    assertThat(cashBalance(fundCash).cents()).isEqualTo(TOTAL_CENTS);

    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    AccountId vestedHolding =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            organizationId.scope() + ":P-001:" + fundId);
    AccountId unvestedHolding =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
            organizationId.scope() + ":P-001:" + fundId);

    long vestedUnits = unitBalance(vestedHolding).scaled(); // employee lot, immediate
    long unvestedUnits = unitBalance(unvestedHolding).scaled(); // employer lot, scheduled
    assertThat(vestedUnits).isEqualTo(300 * Units.SCALE);
    assertThat(unvestedUnits).isEqualTo(150 * Units.SCALE);

    // UNITS_IN_ISSUE is a contra account: Units can never hold a negative value, so
    // TigerBeetleLedgerAdapter reports a contra account's balance in ITS OWN non-negative
    // direction (debitsPosted - creditsPosted, the adapter's own documented convention) --
    // which for an account only ever debited (never credited) is exactly the magnitude
    // debited: the total units issued, matching what participants hold one for one.
    assertThat(unitBalance(unitsInIssue).scaled()).isEqualTo(vestedUnits + unvestedUnits);

    // Every allotment names the NAV version it used, and employee/employer lots are
    // separately readable afterward.
    List<AllotmentRow> rows = allotmentRows(batchId);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> assertThat(r.navVersionId()).isEqualTo(navVersionId));
    assertThat(rows)
        .filteredOn(r -> r.source().equals("EMPLOYEE"))
        .singleElement()
        .satisfies(r -> assertThat(r.vested()).isTrue());
    assertThat(rows)
        .filteredOn(r -> r.source().equals("EMPLOYER"))
        .singleElement()
        .satisfies(r -> assertThat(r.vested()).isFalse());

    // A contribution never moves the NAV -- asserted before and after.
    Long navScaledAfter =
        jdbc.sql("SELECT nav_scaled FROM reference.fund_nav WHERE nav_version_id = :id")
            .param("id", navVersionId)
            .query(Long.class)
            .single();
    assertThat(navScaledAfter).isEqualTo(NAV_SCALED);
  }

  /**
   * Row 26p (TASK-LOG.md step 112): a batch's accounts now open in one ledger request and its audit
   * rows land in multi-row inserts of {@link JdbcAllotmentRepository#ROWS_PER_STATEMENT}. 1,201
   * participants with employee and employer money under scheduled vesting is 2,402 legs: two full
   * insert chunks and a partial one, and 2,405 accounts in one request. Priced at a NAV below the
   * fund's launch price and off any round number, so every leg floors a fraction. Every leg must be
   * recorded once, with the units the ledger holds, and a rerun must add nothing.
   */
  @Test
  @DisplayName(
      "a batch spanning several audit-insert chunks records every leg once, matching the ledger")
  void aBatchSpanningSeveralInsertChunksRecordsEveryLegOnce() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    LocalDate valueDate = LocalDate.of(2026, 8, 19);
    Instant stagedAt = Instant.parse("2026-08-19T09:00:00Z");
    String planId = "PLAN-ACME-CAJA-1";
    UUID batchId = UUID.randomUUID();
    long fallenNavScaled = 973_456_789L; // 9.73456789, down from the 10.00 launch price
    int participants = JdbcAllotmentRepository.ROWS_PER_STATEMENT + 201;

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId, LocalDate.of(2020, 1, 1));
    publishPlanVersion(organizationId, planId, fundId, 5); // employer money unvested
    long navVersionId = strikeNav(fundId, valueDate, fallenNavScaled);
    stageBatch(organizationId, batchId, planId, valueDate, stagedAt);
    List<StagedRow> rows = new ArrayList<>(participants);
    long totalCents = 0;
    for (int i = 0; i < participants; i++) {
      String ref = String.format("P-%05d", i);
      long employee = 100_000L + i * 7L;
      long employer = 50_000L + i * 3L;
      stageRow(organizationId, batchId, ref, employee, employer);
      rows.add(stagedRowRecord(organizationId, batchId, ref, employee, employer));
      totalCents += employee + employer;
    }
    fundEmployerCollections(organizationId, totalCents);
    StagedBatch batch = new StagedBatch(batchId, organizationId, planId, valueDate, stagedAt);

    assertThat(allotmentService.allot(batch, rows)).contains(valueDate);

    Map<String, Long> unitsByLeg =
        jdbc
            .sql(
                "SELECT participant_internal_ref, source, units_scaled, nav_version_id"
                    + " FROM workflow.allotment WHERE batch_id = :batchId")
            .param("batchId", batchId)
            .query(
                (rs, rowNum) -> {
                  assertThat(rs.getLong("nav_version_id")).isEqualTo(navVersionId);
                  return Map.entry(
                      rs.getString("participant_internal_ref") + ":" + rs.getString("source"),
                      rs.getLong("units_scaled"));
                })
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    assertThat(unitsByLeg).hasSize(2 * participants);

    // Each recorded leg is exactly the floor the ledger posted, at chunk edges included.
    for (int i : new int[] {0, 499, 500, 999, 1_000, participants - 1}) {
      String ref = String.format("P-%05d", i);
      long expectedEmployee = floorUnits(100_000L + i * 7L, fallenNavScaled);
      long expectedEmployer = floorUnits(50_000L + i * 3L, fallenNavScaled);
      assertThat(unitsByLeg).containsEntry(ref + ":EMPLOYEE", expectedEmployee);
      assertThat(unitsByLeg).containsEntry(ref + ":EMPLOYER", expectedEmployer);
      String scope = organizationId.scope() + ":" + ref + ":" + fundId;
      assertThat(
              unitBalance(
                      accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope))
                  .scaled())
          .isEqualTo(expectedEmployee);
      assertThat(
              unitBalance(
                      accountIds.derive(
                          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope))
                  .scaled())
          .isEqualTo(expectedEmployer);
    }
    long recordedUnits = unitsByLeg.values().stream().mapToLong(Long::longValue).sum();
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    assertThat(unitBalance(unitsInIssue).scaled()).isEqualTo(recordedUnits);

    // A rerun (the crash-after-chain case) re-opens no account and records nothing twice.
    assertThat(allotmentService.allot(batch, rows)).contains(valueDate);
    assertThat(allotmentRows(batchId)).hasSize(2 * participants);
    assertThat(unitBalance(unitsInIssue).scaled()).isEqualTo(recordedUnits);
  }

  private static long floorUnits(long cents, long navScaled) {
    return BigInteger.valueOf(cents)
        .multiply(BigInteger.valueOf(100_000_000_000_000L))
        .divide(BigInteger.valueOf(navScaled))
        .longValueExact();
  }

  @Test
  @DisplayName("killing the job mid-run and rerunning produces no double allotment")
  void rerunningAnInterruptedAllotmentIsANoOp() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    LocalDate valueDate = LocalDate.of(2026, 8, 19);
    Instant stagedAt = Instant.parse("2026-08-19T09:00:00Z");
    String planId = "PLAN-ACME-CAJA-1";
    UUID batchId = UUID.randomUUID();

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId, LocalDate.of(2020, 1, 1));
    publishPlanVersion(organizationId, planId, fundId, 0); // immediate vesting
    strikeNav(fundId, valueDate, NAV_SCALED);
    fundEmployerCollections(organizationId, EMPLOYEE_CENTS + EMPLOYER_CENTS);
    // workflow.allotment.batch_id is a foreign key against workflow.contribution_batch: a real
    // batch row is required even though this test drives AllotmentService directly rather than
    // through the runner's own sweep.
    stageBatch(organizationId, batchId, planId, valueDate, stagedAt);
    stageRow(organizationId, batchId, "P-001", EMPLOYEE_CENTS, EMPLOYER_CENTS);
    StagedBatch batch = new StagedBatch(batchId, organizationId, planId, valueDate, stagedAt);
    List<StagedRow> rows =
        List.of(stagedRowRecord(organizationId, batchId, "P-001", EMPLOYEE_CENTS, EMPLOYER_CENTS));

    // Two direct calls to AllotmentService, standing in for "the runner crashed after the
    // ledger chain landed but before it marked the batch ALLOTTED, and a later sweep tried
    // again" -- the transaction-per-batch boundary AllotmentRunner itself provides is what
    // the runner-level suite would otherwise need a fault injection to exercise.
    Optional<LocalDate> first = allotmentService.allot(batch, rows);
    Optional<LocalDate> second = allotmentService.allot(batch, rows);

    assertThat(first).isPresent();
    assertThat(second).isEqualTo(first);

    AccountId vestedHolding =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            organizationId.scope() + ":P-001:" + fundId);
    // Both sources land vested under this plan's immediate-vesting policy, so the total is
    // the whole contribution (450.00000000 units at NAV 10.00000000), not doubled by the
    // second call.
    assertThat(unitBalance(vestedHolding).scaled()).isEqualTo(450 * Units.SCALE);

    List<AllotmentRow> allotmentAudit = allotmentRows(batchId);
    assertThat(allotmentAudit).hasSize(2); // one per source, not four
  }

  @Test
  @DisplayName("the run's residual reconciles against FUND_CASH minus the value of units issued")
  void theResidualReconcilesAgainstFundCashMinusUnitsValue() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    LocalDate valueDate = LocalDate.of(2026, 8, 19);
    Instant stagedAt = Instant.parse("2026-08-19T09:00:00Z");
    String planId = "PLAN-ACME-CAJA-1";
    UUID batchId = UUID.randomUUID();
    // An extreme NAV and a 17-cent contribution -- the same hand-verified pair
    // AllotmentServiceTest uses to force a non-zero residual (1 centavo) from a single leg,
    // rather than needing thousands of rows to accumulate one past Money.value's own half-up
    // rounding (see that test's own comment).
    long navScaled = 90_000_000_000_000L; // NAV 900,000.00000000
    long employeeCents = 17L;

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId, LocalDate.of(2020, 1, 1));
    publishPlanVersion(organizationId, planId, fundId, 0); // immediate vesting
    long navVersionId = strikeNav(fundId, valueDate, navScaled);
    stageBatch(organizationId, batchId, planId, valueDate, stagedAt);
    stageRow(organizationId, batchId, "P-001", employeeCents, 0L);
    fundEmployerCollections(organizationId, employeeCents);

    runner.sweep();

    AccountId fundCash = accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fundId.toString());
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    Money unitsValue = Money.value(unitBalance(unitsInIssue), Nav.of(navScaled, CurrencyCode.MXN));
    Money expectedResidual = cashBalance(fundCash).minus(unitsValue);
    assertThat(expectedResidual).isEqualTo(Money.of(1L, CurrencyCode.MXN));

    AllotmentResidualRow residual = allotmentResidual(batchId);
    assertThat(residual.navVersionId()).isEqualTo(navVersionId);
    assertThat(residual.rowCount()).isEqualTo(1);
    assertThat(residual.residualMinorUnits()).isEqualTo(expectedResidual.cents());
    assertThat(residual.residualMinorUnits()).isGreaterThanOrEqualTo(0L);
  }

  private record AllotmentResidualRow(long navVersionId, int rowCount, long residualMinorUnits) {}

  private AllotmentResidualRow allotmentResidual(UUID batchId) {
    return jdbc.sql(
            "SELECT nav_version_id, row_count, residual_minor_units FROM"
                + " workflow.allotment_residual WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .query(
            (rs, rowNum) ->
                new AllotmentResidualRow(
                    rs.getLong("nav_version_id"),
                    rs.getInt("row_count"),
                    rs.getLong("residual_minor_units")))
        .single();
  }

  private StagedRow stagedRowRecord(
      OrganizationId organizationId,
      UUID batchId,
      String ref,
      long employeeCents,
      long employerCents) {
    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    amounts.put(ContributionSource.EMPLOYEE, employeeCents);
    amounts.put(ContributionSource.EMPLOYER, employerCents);
    amounts.put(ContributionSource.VOLUNTARY, 0L);
    return new StagedRow(organizationId, batchId, ref, amounts);
  }

  private Money cashBalance(AccountId id) {
    return ledger
        .lookupAccount(id)
        .map(a -> ((LedgerAccount.Cash) a).balance())
        .orElseThrow(() -> new AssertionError("Account not found: " + id));
  }

  private Units unitBalance(AccountId id) {
    return ledger
        .lookupAccount(id)
        .map(a -> ((LedgerAccount.Unit) a).balance())
        .orElseThrow(() -> new AssertionError("Account not found: " + id));
  }

  private String batchStatus(UUID batchId) {
    return jdbc.sql("SELECT status FROM workflow.contribution_batch WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .query(String.class)
        .single();
  }

  private record AllotmentRow(String source, long navVersionId, boolean vested) {}

  private List<AllotmentRow> allotmentRows(UUID batchId) {
    return jdbc.sql(
            "SELECT source, nav_version_id, vested FROM workflow.allotment WHERE batch_id ="
                + " :batchId")
        .param("batchId", batchId)
        .query(
            (rs, rowNum) ->
                new AllotmentRow(
                    rs.getString("source"), rs.getLong("nav_version_id"), rs.getBoolean("vested")))
        .list();
  }

  private void registerOrganization(OrganizationId organizationId) {
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
  }

  private void registerFund(FundId fundId, long unitLedgerId, LocalDate inceptionDate) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, :inceptionDate, :openingNav, :now)
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("unitScale", Units.SCALE)
        .param("inceptionDate", inceptionDate)
        .param("openingNav", NAV_SCALED)
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, 'Caja de Ahorro Acme', 'CAJA_DE_AHORRO', 'ACTIVE',
                    :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", inceptionDate)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void publishPlanVersion(
      OrganizationId organizationId, String planId, FundId fundId, int vestingFullYears) {
    jdbc.sql(
            """
            INSERT INTO reference.plan_version (
                organization_id, plan_id, effective_from, version,
                employer_match_ratio_numerator, employer_match_ratio_denominator,
                vesting_full_years, dealing_lag_business_days, fund_id, published_at)
            VALUES (:organizationId, :planId, :effectiveFrom, 1, 1, 2, :vestingFullYears, 0,
                    :fundId, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("planId", planId)
        .param("effectiveFrom", LocalDate.of(2020, 1, 1))
        .param("vestingFullYears", vestingFullYears)
        .param("fundId", fundId.value())
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  private long strikeNav(FundId fundId, LocalDate valuationDate, long navScaled) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 0, 'MODEL_B', :now)
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", navScaled)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .query(Long.class)
        .single();
  }

  private void stageBatch(
      OrganizationId organizationId,
      UUID batchId,
      String planId,
      LocalDate valueDate,
      Instant stagedAt) {
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
                    :planId, '2026-08-B', :valueDate,
                    :declaredTotal, 1,
                    :employeeCents, :employerCents, 0,
                    :stagedAt, :now, :now)
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "contribution-batches/" + batchId + ".csv")
        .param("checksum", "a".repeat(64))
        .param("planId", planId)
        .param("valueDate", valueDate)
        .param("declaredTotal", EMPLOYEE_CENTS + EMPLOYER_CENTS)
        .param("employeeCents", EMPLOYEE_CENTS)
        .param("employerCents", EMPLOYER_CENTS)
        .param("stagedAt", Timestamp.from(stagedAt))
        .param("now", Timestamp.from(now))
        .update();
  }

  private void stageRow(
      OrganizationId organizationId,
      UUID batchId,
      String participantRef,
      long employeeCents,
      long employerCents) {
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch_row (
                organization_id, batch_id, participant_internal_ref, participant_payroll_ref,
                plan_id, payroll_period, value_date, employee_minor_units, employer_minor_units,
                voluntary_minor_units, recorded_at)
            VALUES (:organizationId, :batchId, :participantRef, :participantRef,
                    'PLAN-ACME-CAJA-1', '2026-08-B', :valueDate, :employeeCents, :employerCents, 0,
                    :now)
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .param("participantRef", participantRef)
        .param("valueDate", LocalDate.of(2026, 8, 19))
        .param("employeeCents", employeeCents)
        .param("employerCents", employerCents)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  /**
   * Opens {@code BANK_CLEARING} and {@code EMPLOYER_COLLECTIONS[org]} and posts a {@code
   * CASH_RECEIPT} between them, the same real precondition every {@code STAGED} batch has met by
   * the time it reaches this service in production (Task 3.3) -- without it, {@code
   * AllotmentService}'s cash legs would reject with {@code ACCOUNT_NOT_FOUND}: allotment-job never
   * provisions {@code EMPLOYER_COLLECTIONS} itself (only fund-level and participant-level
   * accounts), on the reasoning its own javadoc gives.
   */
  private void fundEmployerCollections(OrganizationId organizationId, long cents) {
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    AccountId bankClearing = accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING);
    ledger.createAccount(
        new CreateAccountCommand(
            bankClearing, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    ledger.createAccount(
        new CreateAccountCommand(
            employerCollections,
            LedgerCodes.CASH_LEDGER_ID,
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));
    ledger.createTransfer(
        CreateTransferCommand.Cash.single(
            TransferId.of(
                ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong()),
            bankClearing,
            employerCollections,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(cents, CurrencyCode.MXN)));
  }

  private long freshLedgerId() {
    // TigerBeetle's ledger field is a uint32 (TigerBeetleLedgerAdapter#doCreateAccount casts it
    // via Math.toIntExact), unlike an AccountId/TransferId's own 128-bit space -- bounded well
    // under Integer.MAX_VALUE, matching production's own small, curated values (LedgerCodes:
    // "unit ledgers from 1001").
    return ThreadLocalRandom.current().nextLong(10_000L, 1_000_000_000L);
  }
}
