package dev.fondaria.allotmentjob.vesting;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.allotmentjob.support.ServiceIntegrationTest;
import dev.fondaria.common.FundId;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 21.4's own acceptance criteria, against a real PostgreSQL and TigerBeetle. Every fixture is
 * seeded directly by SQL and {@link LedgerPort} — {@code workflow.allotment}, {@code
 * master.participant} and the unvested unit balance itself all stand in for a real Task 21.2
 * allotment run having already happened, the same way {@code AllotmentServiceIntegrationTest}
 * stands in for contribution-api and valuation-api.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VestingServiceIntegrationTest extends ServiceIntegrationTest {

  private static final long NAV_SCALED = 10 * Units.SCALE;
  private static final String PLAN_ID = "PLAN-ACME-RETIREMENT";

  @Autowired private VestingRunner runner;
  @Autowired private VestingCandidateRepository candidates;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName(
      "a participant past their vesting horizon has their whole unvested balance moved, leaving"
          + " total units unchanged")
  void theFullPathVestsAndReconciles() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    UUID batchId = UUID.randomUUID();
    long unvestedScaled = 150 * Units.SCALE;

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId);
    publishPlanVersion(organizationId, fundId, 5); // scheduled vesting
    registerParticipant(organizationId, "P-001", LocalDate.now().minusYears(6));
    long navVersionId = strikeNav(fundId, LocalDate.of(2026, 8, 19));
    stageBatch(organizationId, batchId, LocalDate.of(2026, 8, 19));
    postEmployerLotToUnvested(organizationId, fundId, unitLedgerId, "P-001", unvestedScaled);
    recordAllotment(organizationId, batchId, "P-001", fundId, navVersionId, unvestedScaled, false);

    // Not asserted on the sweep's own return count -- fixtures are reused across local runs
    // (docs/ENGINEERING-RULES.md §4), so a sweep this wide can pick up candidates other test runs
    // left
    // behind. Every assertion below is scoped to this test's own organizationId/fundId.
    runner.sweep();

    AccountId unvested =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
            organizationId.scope() + ":P-001:" + fundId);
    AccountId vested =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            organizationId.scope() + ":P-001:" + fundId);

    assertThat(unitBalance(unvested).scaled()).isZero();
    assertThat(unitBalance(vested).scaled()).isEqualTo(unvestedScaled);
  }

  @Test
  @DisplayName("rerunning the sweep the same day moves nothing")
  void rerunningTheSameDayMovesNothing() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    long unitLedgerId = freshLedgerId();
    UUID batchId = UUID.randomUUID();
    long unvestedScaled = 200 * Units.SCALE;

    registerOrganization(organizationId);
    registerFund(fundId, unitLedgerId);
    publishPlanVersion(organizationId, fundId, 5);
    registerParticipant(organizationId, "P-002", LocalDate.now().minusYears(6));
    long navVersionId = strikeNav(fundId, LocalDate.of(2026, 8, 19));
    stageBatch(organizationId, batchId, LocalDate.of(2026, 8, 19));
    postEmployerLotToUnvested(organizationId, fundId, unitLedgerId, "P-002", unvestedScaled);
    recordAllotment(organizationId, batchId, "P-002", fundId, navVersionId, unvestedScaled, false);

    runner.sweep();
    AccountId vested =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            organizationId.scope() + ":P-002:" + fundId);
    long vestedAfterFirstSweep = unitBalance(vested).scaled();

    runner.sweep();
    long vestedAfterSecondSweep = unitBalance(vested).scaled();

    assertThat(vestedAfterFirstSweep).isEqualTo(unvestedScaled);
    assertThat(vestedAfterSecondSweep).isEqualTo(vestedAfterFirstSweep);
  }

  @Test
  @DisplayName("an immediate-vesting plan's allotments never appear as a vesting candidate")
  void immediateVestingPlanNeverAppearsAsACandidate() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    FundId fundId = FundId.of(UUID.randomUUID());
    UUID batchId = UUID.randomUUID();
    registerOrganization(organizationId);
    registerFund(fundId, freshLedgerId());
    long navVersionId = strikeNav(fundId, LocalDate.of(2026, 8, 19));
    stageBatch(organizationId, batchId, LocalDate.of(2026, 8, 19));
    // Task 21.2: an EMPLOYER lot under immediate vesting is recorded vested = true, never
    // false -- so it never enters PARTICIPANT_HOLDINGS_UNVESTED and never becomes a vesting
    // candidate in the first place.
    recordAllotment(
        organizationId, batchId, "P-IMMEDIATE", fundId, navVersionId, 100 * Units.SCALE, true);

    List<VestingCandidate> found = candidates.pendingCandidates(10_000);

    assertThat(found)
        .noneMatch(
            c ->
                c.organizationId().equals(organizationId)
                    && c.participantInternalRef().equals("P-IMMEDIATE"));
  }

  private Units unitBalance(AccountId id) {
    return ledger
        .lookupAccount(id)
        .map(a -> ((LedgerAccount.Unit) a).balance())
        .orElseThrow(() -> new AssertionError("Account not found: " + id));
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

  private void registerFund(FundId fundId, long unitLedgerId) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    LocalDate inceptionDate = LocalDate.of(2015, 1, 1);
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
            VALUES (:fundId, :effectiveFrom, 1, 'Fondo de Retiro Acme', 'RETIREMENT', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", inceptionDate)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void publishPlanVersion(
      OrganizationId organizationId, FundId fundId, int vestingFullYears) {
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
        .param("planId", PLAN_ID)
        .param("effectiveFrom", LocalDate.of(2010, 1, 1))
        .param("vestingFullYears", vestingFullYears)
        .param("fundId", fundId.value())
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  private void registerParticipant(
      OrganizationId organizationId, String participantRef, LocalDate employmentStartDate) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                created_at, updated_at)
            VALUES (:organizationId, :participantRef, :planId, :participantRef,
                    'ACTIVE', 'ACTIVE', :employmentStartDate, :employmentStartDate, :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("participantRef", participantRef)
        .param("planId", PLAN_ID)
        .param("employmentStartDate", employmentStartDate)
        .param("now", Timestamp.from(now))
        .update();
  }

  private long strikeNav(FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 0, 'MODEL_B', :now)
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", NAV_SCALED)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .query(Long.class)
        .single();
  }

  private void stageBatch(OrganizationId organizationId, UUID batchId, LocalDate valueDate) {
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
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'ALLOTTED',
                    :planId, '2026-08-B', :valueDate,
                    1, 1,
                    1, 0, 0,
                    :now, :now, :now)
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "contribution-batches/" + batchId + ".csv")
        .param("checksum", "a".repeat(64))
        .param("planId", PLAN_ID)
        .param("valueDate", valueDate)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Simulates Task 21.2's own {@code CONTRIBUTION_ALLOTMENT} unit leg landing a participant's
   * employer lot in {@code PARTICIPANT_HOLDINGS_UNVESTED} -- the real precondition {@link
   * VestingService} reads a balance from.
   */
  private void postEmployerLotToUnvested(
      OrganizationId organizationId,
      FundId fundId,
      long unitLedgerId,
      String participantRef,
      long unitsScaled) {
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    AccountId unvested =
        accountIds.derive(
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
            organizationId.scope() + ":" + participantRef + ":" + fundId);
    ledger.createAccount(
        new CreateAccountCommand(
            unitsInIssue, unitLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    ledger.createAccount(
        new CreateAccountCommand(
            unvested, unitLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED));
    ledger.createTransfer(
        CreateTransferCommand.Unit.single(
            TransferId.of(
                ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong()),
            unitsInIssue,
            unvested,
            unitLedgerId,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(unitsScaled)));
  }

  private void recordAllotment(
      OrganizationId organizationId,
      UUID batchId,
      String participantRef,
      FundId fundId,
      long navVersionId,
      long unitsScaled,
      boolean vested) {
    jdbc.sql(
            """
            INSERT INTO workflow.allotment (
                organization_id, batch_id, participant_internal_ref, source, fund_id,
                nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            VALUES (:organizationId, :batchId, :participantRef, 'EMPLOYER', :fundId,
                    :navVersionId, :unitsScaled, :vested, :transferId, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .param("participantRef", participantRef)
        .param("fundId", fundId.value())
        .param("navVersionId", navVersionId)
        .param("unitsScaled", unitsScaled)
        .param("vested", vested)
        .param("transferId", UUID.randomUUID())
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  private long freshLedgerId() {
    return ThreadLocalRandom.current().nextLong(10_000L, 1_000_000_000L);
  }
}
