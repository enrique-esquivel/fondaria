package dev.fondaria.allotmentjob.allotment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.allotmentjob.batch.ContributionSource;
import dev.fondaria.allotmentjob.batch.StagedBatch;
import dev.fondaria.allotmentjob.batch.StagedRow;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerLimits;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link AllotmentService} against fakes — no database, no ledger. What this suite pins down is the
 * pricing and routing logic itself: the unit division, the vested/unvested split, and the shape of
 * the ledger chain it builds, using chart-of-accounts.md §6's own worked example as the numeric
 * ground truth. {@code AllotmentServiceIntegrationTest} proves the same logic against a real
 * TigerBeetle and PostgreSQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AllotmentServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  // A Wednesday, matching the plan's zero dealing lag and a same-day-staged batch, so the
  // dealing date this test computes is exactly the batch's own value date.
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 19);
  private static final Instant STAGED_AT = Instant.parse("2026-08-19T09:00:00Z");
  private static final String PLAN_ID = "PLAN-ACME-CAJA-1";
  private static final long NAV_SCALED = 10 * Units.SCALE; // 10.00000000
  private static final long FUND_LEDGER_ID = 5001L;

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = FundId.of(UUID.randomUUID());
  private final BusinessCalendar calendar =
      new BusinessCalendar(ZoneOffset.UTC, LocalTime.of(18, 0), Set.of());
  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TransferIdDeriver transferIds = new TransferIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final RecordingLedgerPort ledger = new RecordingLedgerPort();
  private final InMemoryDirectories directories = new InMemoryDirectories();
  private final InMemoryAllotmentRepository allotments = new InMemoryAllotmentRepository();
  private final InMemoryAllotmentResidualRepository residuals =
      new InMemoryAllotmentResidualRepository();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AllotmentService service =
      new AllotmentService(
          directories,
          directories,
          directories,
          calendar,
          ledger,
          accountIds,
          transferIds,
          tenantRefs,
          allotments,
          residuals,
          clock,
          new AllotmentMetrics(meterRegistry));

  @BeforeEach
  void publishFundAndPlan() {
    directories.givenFund(
        new Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Caja de Ahorro Acme",
            "CAJA_DE_AHORRO",
            CurrencyCode.MXN,
            FUND_LEDGER_ID,
            Units.SCALE,
            LocalDate.of(2020, 1, 1),
            Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.parse("2020-01-01T00:00:00Z")));
  }

  private PlanVersion planVersion(int vestingFullYears) {
    return new PlanVersion(
        organizationId,
        PLAN_ID,
        LocalDate.of(2020, 1, 1),
        1,
        1,
        2,
        vestingFullYears,
        0,
        fundId,
        Instant.parse("2020-01-01T00:00:00Z"));
  }

  private StagedBatch batch() {
    return new StagedBatch(UUID.randomUUID(), organizationId, PLAN_ID, VALUE_DATE, STAGED_AT);
  }

  private StagedRow row(
      UUID batchId, String participantRef, long employeeCents, long employerCents) {
    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    amounts.put(ContributionSource.EMPLOYEE, employeeCents);
    amounts.put(ContributionSource.EMPLOYER, employerCents);
    amounts.put(ContributionSource.VOLUNTARY, 0L);
    return new StagedRow(organizationId, batchId, participantRef, amounts);
  }

  @Test
  void a_batch_allots_to_the_cent_and_to_the_unit_matching_the_worked_example() {
    directories.givenPlanVersion(planVersion(5)); // scheduled (non-immediate) vesting
    directories.givenNav(
        new FundNav(
            42L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();
    List<StagedRow> rows =
        List.of(
            row(batch.batchId(), "P-001", 300_000L, 150_000L),
            row(batch.batchId(), "P-002", 200_000L, 100_000L));

    Optional<LocalDate> dealingDate = service.allot(batch, rows);

    assertThat(dealingDate).contains(VALUE_DATE);
    assertThat(ledger.linkedChains()).hasSize(1);
    List<CreateTransferCommand> chain = ledger.linkedChains().get(0);
    assertThat(chain).hasSize(6); // 2 cash legs + 4 unit legs (2 participants x 2 sources)
    assertThat(
            meterRegistry
                .get("transfer_created_total")
                .tag("code", "CONTRIBUTION_ALLOTMENT")
                .counter()
                .count())
        .as("one increment per leg the chain actually posted")
        .isEqualTo(6.0);

    // Cash: the whole batch total, 7,500.00 MXN, moved twice.
    Money total = Money.of(750_000L, CurrencyCode.MXN);
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    AccountId allotmentSuspense =
        accountIds.derive(LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE, fundId.toString());
    AccountId fundCash = accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, fundId.toString());
    assertThat(chain.get(0))
        .isInstanceOfSatisfying(
            CreateTransferCommand.Cash.class,
            cash -> {
              assertThat(cash.debitAccountId()).isEqualTo(employerCollections);
              assertThat(cash.creditAccountId()).isEqualTo(allotmentSuspense);
              assertThat(cash.amount()).isEqualTo(total);
            });
    assertThat(chain.get(1))
        .isInstanceOfSatisfying(
            CreateTransferCommand.Cash.class,
            cash -> {
              assertThat(cash.debitAccountId()).isEqualTo(allotmentSuspense);
              assertThat(cash.creditAccountId()).isEqualTo(fundCash);
              assertThat(cash.amount()).isEqualTo(total);
            });

    // Units: chart-of-accounts.md §6's own numbers. Employee lots vested immediately; employer
    // lots unvested under this plan's scheduled (non-zero vestingFullYears) policy.
    assertUnitLeg(chain.get(2), "P-001", ContributionSource.EMPLOYEE, 300 * Units.SCALE, true);
    assertUnitLeg(chain.get(3), "P-001", ContributionSource.EMPLOYER, 150 * Units.SCALE, false);
    assertUnitLeg(chain.get(4), "P-002", ContributionSource.EMPLOYEE, 200 * Units.SCALE, true);
    assertUnitLeg(chain.get(5), "P-002", ContributionSource.EMPLOYER, 100 * Units.SCALE, false);

    // UNITS_IN_ISSUE moves by exactly the sum credited to participants -- 750.00000000 units,
    // matching the worked example's own reconciliation.
    long unitsDebited =
        chain.stream()
            .skip(2)
            .mapToLong(cmd -> ((CreateTransferCommand.Unit) cmd).amount().scaled())
            .sum();
    assertThat(unitsDebited).isEqualTo(750 * Units.SCALE);

    // Every allotment is recorded with the NAV version it used, and sources are separately
    // readable afterward.
    assertThat(allotments.recorded()).hasSize(4);
    assertThat(allotments.recorded()).allSatisfy(a -> assertThat(a.navVersionId()).isEqualTo(42L));
    assertThat(
            allotments.recorded().stream()
                .filter(a -> a.source() == ContributionSource.EMPLOYEE)
                .allMatch(AllotmentRecord::vested))
        .isTrue();
    assertThat(
            allotments.recorded().stream()
                .filter(a -> a.source() == ContributionSource.EMPLOYER)
                .noneMatch(AllotmentRecord::vested))
        .isTrue();
  }

  private void assertUnitLeg(
      CreateTransferCommand command,
      String participantRef,
      ContributionSource source,
      long expectedUnitsScaled,
      boolean expectedVested) {
    assertThat(command).isInstanceOf(CreateTransferCommand.Unit.class);
    CreateTransferCommand.Unit unit = (CreateTransferCommand.Unit) command;
    assertThat(unit.fundLedgerId()).isEqualTo(FUND_LEDGER_ID);
    assertThat(unit.amount()).isEqualTo(Units.of(expectedUnitsScaled));
    LedgerCodes.AccountCode holdingCode =
        expectedVested
            ? LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED
            : LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED;
    AccountId expectedHolding =
        accountIds.derive(
            holdingCode, organizationId.scope() + ":" + participantRef + ":" + fundId);
    assertThat(unit.creditAccountId()).isEqualTo(expectedHolding);
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    assertThat(unit.debitAccountId()).isEqualTo(unitsInIssue);
  }

  @Test
  void a_contribution_leaves_nav_per_unit_unchanged() {
    directories.givenPlanVersion(planVersion(5));
    FundNav struck =
        new FundNav(
            1L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z"));
    directories.givenNav(struck);
    StagedBatch batch = batch();

    service.allot(batch, List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L)));

    assertThat(directories.findOriginal(fundId, VALUE_DATE)).contains(struck);
  }

  @Test
  void immediate_vesting_routes_employer_money_to_vested_too() {
    directories.givenPlanVersion(planVersion(0)); // immediate vesting
    directories.givenNav(
        new FundNav(
            1L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();

    service.allot(batch, List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L)));

    assertThat(allotments.recorded()).allSatisfy(a -> assertThat(a.vested()).isTrue());
    List<CreateTransferCommand> chain = ledger.linkedChains().get(0);
    assertThat(chain)
        .noneMatch(
            cmd ->
                cmd.creditAccountId()
                    .equals(
                        accountIds.derive(
                            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
                            organizationId.scope() + ":P-001:" + fundId)));
  }

  @Test
  void not_ready_when_no_nav_has_been_struck_for_the_dealing_date() {
    directories.givenPlanVersion(planVersion(5));
    StagedBatch batch = batch();

    Optional<LocalDate> result =
        service.allot(batch, List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L)));

    assertThat(result).isEmpty();
    assertThat(ledger.linkedChains()).isEmpty();
    assertThat(ledger.createdAccounts()).isEmpty();
    assertThat(allotments.recorded()).isEmpty();
  }

  @Test
  void not_ready_when_the_plan_version_does_not_resolve() {
    StagedBatch batch = batch();

    Optional<LocalDate> result =
        service.allot(batch, List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L)));

    assertThat(result).isEmpty();
    assertThat(ledger.linkedChains()).isEmpty();
  }

  @Test
  void a_ledger_rejection_is_a_genuine_failure() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenNav(
        new FundNav(
            1L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    ledger.rejectEveryTransferWith(LedgerRejectionReason.ACCOUNT_NOT_FOUND);
    StagedBatch batch = batch();
    List<StagedRow> rows = List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L));

    assertThatThrownBy(() -> service.allot(batch, rows)).isInstanceOf(IllegalStateException.class);
    assertThat(
            meterRegistry
                .get("transfer_rejected_total")
                .tag("reason", "ACCOUNT_NOT_FOUND")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void
      a_leg_whose_floor_discards_more_than_half_a_cent_records_a_positive_residual_that_reconciles_exactly() {
    directories.givenPlanVersion(planVersion(0)); // vesting policy is irrelevant to rounding
    // An extreme NAV, deliberately not a clean divisor of 1e14 — chart-of-accounts.md §6's own
    // MXN 10.00000000 (nav_scaled 1e9) divides 1e14 evenly and never leaves a remainder for any
    // whole-cent amount, which is why that worked example alone would never exercise this rule.
    // This NAV is also large enough that a single leg's discarded fraction crosses half a cent
    // and is visible without needing thousands of rows to accumulate past Money.value's own
    // half-up rounding — see the 200,000-row test below for the realistic-NAV case.
    long extremeNavScaled = 90_000_000_000_000L; // NAV 900,000.00000000
    Nav nav = Nav.of(extremeNavScaled, CurrencyCode.MXN);
    directories.givenNav(
        new FundNav(
            9L,
            fundId,
            VALUE_DATE,
            nav,
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();
    List<StagedRow> rows = List.of(row(batch.batchId(), "P-001", 17L, 0L));

    service.allot(batch, rows);

    List<CreateTransferCommand> chain = ledger.linkedChains().get(0);
    long unitsIssued =
        chain.stream()
            .skip(2)
            .mapToLong(cmd -> ((CreateTransferCommand.Unit) cmd).amount().scaled())
            .sum();
    Money total = Money.of(17L, CurrencyCode.MXN);
    Money expectedResidual = total.minus(Money.value(Units.of(unitsIssued), nav));

    AllotmentResidual recorded = residuals.recordedFor(batch.batchId());
    assertThat(recorded.residual()).isEqualTo(expectedResidual);
    // Hand-verified: MXN 0.17 buys 18 ticks (0.00000018 units) at this NAV, worth MXN 0.16 --
    // the floor discards exactly one centavo of value, never negative and never absorbed
    // silently.
    assertThat(recorded.residual()).isEqualTo(Money.of(1L, CurrencyCode.MXN));
    assertThat(recorded.rowCount()).isEqualTo(1);
    assertThat(recorded.navVersionId()).isEqualTo(9L);
    assertThat(recorded.fundId()).isEqualTo(fundId);
  }

  /**
   * Task 21.3's rounding claim at the demo's own scale, told the way ADR-0017 makes it run: a
   * 200,000-row file is not one chain and never can be, so it is 25 parts of 8,000 rows, each
   * allotted and each recording its own residual.
   *
   * <p><b>Splitting moves the residual, it does not lose it.</b> Every centavo an employer sent is
   * still either units in issue or a recorded residue — that is the assertion that matters, and it
   * is made over the whole file rather than one part. What splitting does cost is precision in the
   * bound: 25 runs each floor their own value, so the file's residue is bounded by the sum of 25
   * per-part ceilings rather than by one whole-file ceiling of three centavos. A quarter of a peso
   * across 200,000 participants, recorded rather than absorbed.
   */
  @Test
  void a_200_000_row_file_allots_as_parts_and_every_centavo_is_units_or_recorded_residue() {
    directories.givenPlanVersion(planVersion(0));
    // A realistic-looking NAV (docs/DOMAIN.md's own example price) that is
    // still not a clean divisor of 1e14, so nearly every leg's floor discards a small fraction
    // of a unit's value.
    long navScaled = 1_473_830_000L; // NAV 14.73830000
    Nav nav = Nav.of(navScaled, CurrencyCode.MXN);
    directories.givenNav(
        new FundNav(
            11L,
            fundId,
            VALUE_DATE,
            nav,
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));

    int rowCount = 200_000;
    int rowsPerPart = 8_000; // one leg per row here, comfortably inside the chain limit
    long fileCents = 0L;
    long filePricedCents = 0L;
    long fileResidualCents = 0L;

    for (int start = 0; start < rowCount; start += rowsPerPart) {
      StagedBatch part = batch();
      List<StagedRow> rows = new ArrayList<>(rowsPerPart);
      for (int i = start; i < start + rowsPerPart; i++) {
        // Employee-only (one leg per row) and an amount that varies per row, so this isn't
        // 200,000 identical divisions.
        rows.add(row(part.batchId(), "P-" + i, 1_000L + (i % 97), 0L));
      }

      service.allot(part, List.copyOf(rows));

      List<CreateTransferCommand> chain =
          ledger.linkedChains().get(ledger.linkedChains().size() - 1);
      assertThat(chain).hasSizeLessThanOrEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
      long partUnits =
          chain.stream()
              .skip(2)
              .mapToLong(cmd -> ((CreateTransferCommand.Unit) cmd).amount().scaled())
              .sum();
      long partCents = rows.stream().mapToLong(r -> r.amountOf(ContributionSource.EMPLOYEE)).sum();

      AllotmentResidual recorded = residuals.recordedFor(part.batchId());
      assertThat(recorded.rowCount()).isEqualTo(rowsPerPart);
      assertThat(recorded.residual())
          .isEqualTo(
              Money.of(partCents, CurrencyCode.MXN).minus(Money.value(Units.of(partUnits), nav)));
      assertThat(recorded.residual().isNegative()).isFalse();
      // docs/rounding-policy.md's ceiling for this part: ceil(8,000 x 1,473,830,000 / 1e14) =
      // ceil(0.1179) = 1 centavo.
      assertThat(recorded.residual().cents()).isLessThanOrEqualTo(1L);

      fileCents += partCents;
      filePricedCents += Money.value(Units.of(partUnits), nav).cents();
      fileResidualCents += recorded.residual().cents();
    }

    assertThat(ledger.linkedChains()).hasSize(rowCount / rowsPerPart);
    // Nothing was created and nothing vanished between the parts: the file's cash is exactly the
    // value of the units its parts issued, plus the residue those parts recorded.
    //
    // Summed PART BY PART, and that is not a formality. Valuing the file's units in one
    // multiplication instead floors once where this floors twenty-five times, and lands a couple
    // of centavos lower -- a rendering of one number in pesos, not cash that went anywhere. Units
    // are what participants hold (docs/ENGINEERING-RULES.md §2); the peso figure is a
    // multiplication performed at
    // whatever granularity it is asked for, which is exactly what ADR-0014 already says.
    assertThat(fileCents).isEqualTo(filePricedCents + fileResidualCents);
    assertThat(fileResidualCents).isBetween(0L, (long) (rowCount / rowsPerPart));
  }

  @Test
  void killing_the_job_mid_run_and_rerunning_produces_no_double_allotment() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenNav(
        new FundNav(
            7L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();
    List<StagedRow> rows = List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L));

    // First attempt "crashes" -- the ledger already created every transfer, but the caller
    // (AllotmentRunner, in production) never got to mark the batch ALLOTTED.
    Optional<LocalDate> firstAttempt = service.allot(batch, rows);

    // The rerun derives the identical first-leg id, finds it already posted, and -- per
    // AllotmentService's own javadoc on why it checks before resending rather than resending
    // and inspecting the result -- never calls createLinkedChain a second time at all.
    Optional<LocalDate> secondAttempt = service.allot(batch, rows);

    assertThat(firstAttempt).contains(VALUE_DATE);
    assertThat(secondAttempt).isEqualTo(firstAttempt);
    assertThat(ledger.linkedChains()).hasSize(1);
    assertThat(allotments.recorded()).hasSize(2); // one per source, not doubled
  }

  @Test
  void every_movement_carries_the_batch_tenant_and_only_pooled_accounts_carry_none() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenNav(
        new FundNav(
            42L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();
    List<StagedRow> rows = List.of(row(batch.batchId(), "P-001", 300_000L, 150_000L));

    service.allot(batch, rows);

    TenantRef expected = tenantRefs.derive(organizationId);

    // Every leg of the chain — the two pooled cash legs included — is attributed to the batch's
    // organization, so projection.transfer can partition tenant-first (ADR-0005).
    assertThat(ledger.linkedChains().get(0))
        .allSatisfy(cmd -> assertThat(cmd.tenant()).isEqualTo(expected));

    // The participant holdings account carries the ref; the pooled fund-level accounts do not.
    assertThat(ledger.createdAccounts())
        .filteredOn(a -> a.accountCode() == LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED)
        .allSatisfy(a -> assertThat(a.tenant()).isEqualTo(expected));
    assertThat(ledger.createdAccounts())
        .filteredOn(
            a ->
                a.accountCode() == LedgerCodes.AccountCode.FUND_CASH
                    || a.accountCode() == LedgerCodes.AccountCode.UNITS_IN_ISSUE
                    || a.accountCode() == LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE)
        .allSatisfy(a -> assertThat(a.tenant().isNone()).isTrue());
  }

  /**
   * Row 26p (TASK-LOG.md step 112): a batch's accounts — the three pooled fund-level accounts and
   * every participant holding — go to the ledger as one request, not one request per account. The
   * ledger serves a bounded number of requests per second however small each is, and ~8,000
   * one-account requests per batch were what stopped extra allotment replicas from adding
   * throughput. A holding two legs share (employee and voluntary money both land vested) is opened
   * once.
   */
  @Test
  void a_batch_opens_every_account_it_needs_in_one_ledger_request() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenNav(
        new FundNav(
            43L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));
    StagedBatch batch = batch();
    Map<ContributionSource, Long> threeSources = new EnumMap<>(ContributionSource.class);
    threeSources.put(ContributionSource.EMPLOYEE, 300_000L);
    threeSources.put(ContributionSource.EMPLOYER, 150_000L);
    threeSources.put(ContributionSource.VOLUNTARY, 50_000L);
    List<StagedRow> rows =
        List.of(
            new StagedRow(organizationId, batch.batchId(), "P-001", threeSources),
            row(batch.batchId(), "P-002", 200_000L, 100_000L),
            row(batch.batchId(), "P-003", 100_000L, 50_000L));

    service.allot(batch, rows);

    assertThat(ledger.accountRequests()).isEqualTo(1);
    // 3 pooled + 2 holdings (vested, unvested) for each of 3 participants; P-001's voluntary
    // leg shares its vested holding with its employee leg.
    assertThat(ledger.createdAccounts()).hasSize(9);
    assertThat(ledger.createdAccounts())
        .extracting(CreateAccountCommand::id)
        .doesNotHaveDuplicates();
    // Every account a unit leg touches was in that one request.
    assertThat(ledger.createdAccounts())
        .extracting(CreateAccountCommand::id)
        .containsAll(
            ledger.linkedChains().get(0).stream()
                .filter(cmd -> cmd instanceof CreateTransferCommand.Unit)
                .flatMap(cmd -> Stream.of(cmd.debitAccountId(), cmd.creditAccountId()))
                .toList());
  }

  /**
   * ADR-0017's guarantee, asserted from the side that would break. contribution-api sizes a batch
   * so its chain fits; this is what happens if it ever stops doing so — a named failure naming the
   * batch, rather than a {@code LedgerUnavailableException} that reads exactly like a broken
   * cluster at the moment an operator most needs to tell those apart.
   */
  @Test
  void a_batch_too_large_for_one_linked_chain_is_refused_by_name_and_nothing_is_posted() {
    directories.givenPlanVersion(planVersion(0));
    directories.givenNav(
        new FundNav(
            42L,
            fundId,
            VALUE_DATE,
            Nav.of(NAV_SCALED, CurrencyCode.MXN),
            Units.of(0),
            "MODEL_B",
            null,
            null,
            Instant.parse("2026-08-19T08:00:00Z")));

    StagedBatch batch = batch();
    // 4,095 rows × two lots + two cash legs is 8,192 — three transfers over the ceiling.
    List<StagedRow> rows = new ArrayList<>();
    for (int i = 0; i < 4_095; i++) {
      rows.add(row(batch.batchId(), "P-" + i, 300_000L, 150_000L));
    }

    assertThatThrownBy(() -> service.allot(batch, List.copyOf(rows)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(String.valueOf(LedgerLimits.MAX_LINKED_CHAIN_LENGTH))
        .hasMessageContaining(batch.batchId().toString());

    // Refused before anything was sent, so there is no half-posted file to reconcile.
    assertThat(ledger.linkedChains()).isEmpty();
  }
}
