package dev.fondaria.e2e;

import static dev.fondaria.e2e.Convergence.awaitConvergence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.reconciliation.BreakScope;
import dev.fondaria.eodjob.reconciliation.CloseBlockedException;
import dev.fondaria.eodjob.reconciliation.Derivation;
import dev.fondaria.eodjob.reconciliation.FundUnitTie;
import dev.fondaria.eodjob.reconciliation.ReconciliationBreak;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.reconciliation.ReconciliationResult;
import dev.fondaria.eodjob.reconciliation.ReconciliationRunner;
import dev.fondaria.eodjob.reconciliation.ReconciliationService;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 7.2 against the running platform: <b>a book that travelled the whole path ties, an injected
 * discrepancy does not, and drift stops the close.</b>
 *
 * <p>Three of the task's four acceptance criteria live here — the soak, the injected discrepancy,
 * and blocking-and-paging. The fourth, a million participants inside the window, is {@code
 * ReconciliationScaleIntegrationTest}'s: it needs a million rows and no contribution path at all.
 *
 * <h2>Why the assertions are scoped to this suite's own fund and organization</h2>
 *
 * <p>A reconciliation pass is, by construction, a total over the whole platform — and the fixtures
 * here are shared and never reset (AGENTS.md §4), so the platform-wide verdict is a statement about
 * every run that ever used this database, not about this one. Every figure asserted below is
 * therefore read from the tie for <b>this run's own fund</b> and the breaks naming it. That is not
 * a weakening: a fund nothing before this run had registered has exactly this suite's units in it,
 * and "these three derivations of my fund agree to the unit" is the criterion in full.
 *
 * <h2>Why the soak is one file</h2>
 *
 * <p>Fifty thousand contributions arrive the way fifty thousand contributions actually arrive: one
 * employer, one wire, one dispersion file, split by the platform into as many linked chains as it
 * takes (ADR-0017). The alternative — fifty thousand one-row files — would exercise employer
 * registration fifty thousand times and the contribution path once each.
 */
@Tag("integration")
class InternalReconciliationEndToEndTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(InternalReconciliationEndToEndTest.class);

  /** Overridable for a local run: {@code -Dreconciliation.soakContributions=2000}. */
  private static final int DEFAULT_SOAK_CONTRIBUTIONS = 50_000;

  /** MXN 300.00 employee and MXN 150.00 employer — the plan's own 1:2 matching ratio. */
  private static final long EMPLOYEE_CENTS = 30_000L;

  private static final long EMPLOYER_CENTS = 15_000L;

  /**
   * A wider budget than {@link Convergence#BUDGET}: the soak posts a hundred thousand unit legs and
   * every one of them travels the CDC path before the projection can agree with the ledger. Nothing
   * here measures latency — this is the point past which "the pipeline is stuck" is the better
   * explanation.
   */
  private static final Duration SOAK_CONVERGENCE = Duration.ofMinutes(20);

  private static PlatformStack stack;
  private static LedgerView ledger;
  private static ReconciliationService reconciliation;
  private static ReconciliationGate gate;
  private static JdbcClient jdbc;

  @BeforeAll
  static void raiseTheStack() {
    stack = PlatformStack.getInstance();
    ledger = new LedgerView(stack);
    reconciliation = stack.eodJobBean(ReconciliationService.class);
    gate = stack.eodJobBean(ReconciliationGate.class);
    jdbc = stack.contributionApiBean(JdbcClient.class);
  }

  // -------------------------------------------------------------------------------------------
  // "Zero drift across a 50,000-contribution soak."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "fifty thousand contributions travel cash → file → allotted → CDC → projection, and all"
          + " three derivations of the fund's units agree to the unit")
  void aSoakLeavesNoDrift() {
    int contributions =
        Integer.getInteger("reconciliation.soakContributions", DEFAULT_SOAK_CONTRIBUTIONS);
    Scenario.TargetFund fund = Scenario.TargetFund.fresh(stack);

    BulkContribution soak =
        BulkContribution.contribute(
            stack,
            fund,
            contributions,
            EMPLOYEE_CENTS,
            EMPLOYER_CENTS,
            Scenario.TEN_PESOS_PER_UNIT);

    // MXN 300.00 and MXN 150.00 at MXN 10.00 per unit: 30 and 15 units each, written out rather
    // than divided here, so a wrong divisor in the platform cannot be matched by a wrong divisor
    // in the expectation.
    long expectedUnitsScaled = (30L + 15L) * Units.SCALE * contributions;
    assertThat(ledger.unitsInIssue(soak.fundId()))
        .as("the fund this run registered has no other holder")
        .isEqualTo(Units.of(expectedUnitsScaled));

    awaitConvergence(
        "every one of the soak's unit legs reaches projection.participant_units and cash legs reach projection.transfer",
        SOAK_CONVERGENCE,
        () ->
            projectedUnits(soak.unitLedgerId()) == expectedUnitsScaled
                && projectedCash(soak.fundId(), LedgerCodes.AccountCode.FUND_CASH)
                    == soak.totalCents());

    ReconciliationResult result = reconciliation.reconcile();
    FundUnitTie tie = result.tieForLedger(soak.unitLedgerId()).orElseThrow();

    assertThat(tie.ledgerParticipantUnits())
        .as(
            "Σ over %d participants' vested and unvested accounts, read one account at a time"
                + " through LedgerPort",
            contributions)
        .isEqualTo(Units.of(expectedUnitsScaled));
    assertThat(tie.ledgerUnitsInIssue())
        .as("UNITS_IN_ISSUE was debited by exactly what the participants were credited")
        .isEqualTo(Units.of(expectedUnitsScaled));
    assertThat(tie.projectionUnits())
        .as(
            "and the projection, having travelled CDC → RabbitMQ → bridge → Redpanda → PostgreSQL,"
                + " arrived at the same number")
        .isEqualTo(Units.of(expectedUnitsScaled));
    assertThat(tie.ties()).isTrue();
    assertThat(result.breaksForLedger(soak.unitLedgerId()))
        .as("zero drift, and not zero drift within a tolerance — there is no tolerance")
        .isEmpty();
    assertThat(tie.holdingAccountsFound())
        .as("both of each participant's holding accounts were found and summed")
        .isEqualTo(2L * contributions);

    // The cash half of the same tie, on the accounts this run's own money moved through.
    assertThat(
            cashTie(result, soak.fundId(), dev.fondaria.ledger.LedgerCodes.AccountCode.FUND_CASH))
        .as("every centavo wired reached FUND_CASH, and the projection saw it arrive")
        .isEqualTo(Money.of(soak.totalCents(), CurrencyCode.MXN));

    LOGGER.info(
        "soak reconciled: contributions={} unitsScaled={} durationMillis={}",
        contributions,
        expectedUnitsScaled,
        result.run().durationMillis().orElseThrow());
  }

  // -------------------------------------------------------------------------------------------
  // "An injected discrepancy is detected and quantified exactly", and
  // "drift blocks the rest of the chain and pages."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a holding row inserted straight into the projection is detected, quantified to the unit,"
          + " and blocks the close")
  void anInjectedProjectionRowIsDetectedQuantifiedAndBlocks() {
    Scenario scenario =
        Scenario.contribute(stack, EMPLOYEE_CENTS, EMPLOYER_CENTS, Scenario.TEN_PESOS_PER_UNIT);
    long realUnitsScaled = (30L + 15L) * Units.SCALE;

    awaitConvergence(
        "the contribution's unit legs reach projection.participant_units and cash legs reach projection.transfer",
        () ->
            projectedUnits(scenario.unitLedgerId()) == realUnitsScaled
                && projectedCash(scenario.fundId(), LedgerCodes.AccountCode.FUND_CASH)
                    == scenario.totalCents());

    // ---- Before: the fund ties, so anything found afterwards is what was injected.
    ReconciliationResult clean = reconciliation.reconcile();
    assertThat(clean.tieForLedger(scenario.unitLedgerId()).orElseThrow().ties()).isTrue();
    assertThat(clean.breaksForLedger(scenario.unitLedgerId())).isEmpty();

    // ---- The injection: a row written with the migration role, which is the only role that can.
    // fondaria_app holds SELECT on projection tables and nothing more (V1), and the CDC role holds
    // INSERT for one consumer. This is the criterion's own scenario: someone with elevated rights
    // put a holding in the projection that no ledger movement produced.
    long phantomUnitsScaled = 77L * Units.SCALE;
    injectPhantomHolding(scenario, phantomUnitsScaled);

    ReconciliationResult drifted = reconciliation.reconcile();

    FundUnitTie tie = drifted.tieForLedger(scenario.unitLedgerId()).orElseThrow();
    assertThat(tie.ledgerParticipantUnits())
        .as("the ledger is unchanged — nothing moved, a row was written")
        .isEqualTo(Units.of(realUnitsScaled));
    assertThat(tie.projectionUnits()).isEqualTo(Units.of(realUnitsScaled + phantomUnitsScaled));

    List<ReconciliationBreak> raised = drifted.breaksForLedger(scenario.unitLedgerId());
    assertThat(raised)
        .as("the ledger still agrees with itself, so only the projection comparison breaks")
        .hasSize(1);
    ReconciliationBreak found = raised.get(0);
    assertThat(found.scope()).isEqualTo(BreakScope.FUND_UNITS);
    assertThat(found.left()).isEqualTo(Derivation.LEDGER_PARTICIPANT_UNITS);
    assertThat(found.right()).isEqualTo(Derivation.PROJECTION_UNITS);
    assertThat(found.driftScaled())
        .as(
            "quantified exactly: 77 units the projection holds and the ledger never issued,"
                + " signed to say which side is short")
        .isEqualTo(-phantomUnitsScaled);

    // ---- Recorded where an operator reads it, with the same figure.
    assertThat(driftOfPersistedBreak(drifted, scenario.unitLedgerId()))
        .isEqualTo(-phantomUnitsScaled);

    // ---- Blocked: the close for this business date cannot proceed.
    LocalDate businessDate = drifted.run().businessDate();
    assertThat(drifted.run().status()).isEqualTo(RunStatus.FAILED);
    assertThat(gate.isOpen(businessDate)).isFalse();
    assertThatThrownBy(() -> gate.requirePassed(businessDate))
        .isInstanceOf(CloseBlockedException.class)
        .hasMessageContaining(businessDate.toString());

    // ---- And paged: the gauge an alert rule watches is raised after the scheduled pass runs.
    stack.eodJobBean(ReconciliationRunner.class).sweep();
    assertThat(
            stack.eodJobBean(MeterRegistry.class).get("eod_reconciliation_blocked").gauge().value())
        .as("blocking is silent on its own; this is how a human finds out")
        .isEqualTo(1.0);
  }

  // ---- helpers --------------------------------------------------------------------------

  /**
   * Σ of the latest projection row per participant for one fund — the third derivation's source.
   */
  private static long projectedUnits(long unitLedgerId) {
    Long total =
        jdbc.sql(
                """
                SELECT SUM(latest.units_scaled)
                  FROM (SELECT DISTINCT ON (tenant_id, participant_id, fund_id) units_scaled
                          FROM projection.participant_units
                         WHERE fund_id = :fundId
                         ORDER BY tenant_id, participant_id, fund_id, effective_date DESC) latest
                """)
            .param("fundId", unitLedgerId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    return total == null ? 0L : total;
  }

  /**
   * Reconciled cash balance of one account in {@code projection.transfer} on the cash ledger (484).
   */
  private static long projectedCash(FundId fundId, LedgerCodes.AccountCode code) {
    AccountId id = stack.eodJobBean(AccountIdDeriver.class).derive(code, fundId.value().toString());
    Long total =
        jdbc.sql(
                """
                SELECT SUM(leg.delta)::bigint
                  FROM (SELECT credit_account_id AS account_id, amount_scaled AS delta
                          FROM projection.transfer
                         WHERE ledger_id = :cashLedgerId
                         UNION ALL
                        SELECT debit_account_id AS account_id, -amount_scaled AS delta
                          FROM projection.transfer
                         WHERE ledger_id = :cashLedgerId) leg
                 WHERE leg.account_id = :accountId
                """)
            .param("cashLedgerId", LedgerCodes.CASH_LEDGER_ID)
            .param("accountId", new java.math.BigDecimal(id.asUnsignedDecimal()))
            .query(Long.class)
            .optional()
            .orElse(0L);
    return total == null ? 0L : total;
  }

  /**
   * A holding the ledger never issued, written directly with the connection's own (migration) role.
   *
   * <p>The participant id is a fresh random u128 rather than an existing account's, so it adds a
   * position rather than restating one — which is the discrepancy worth catching: an account the
   * ledger has never heard of, holding units nobody bought.
   */
  private static void injectPhantomHolding(Scenario scenario, long unitsScaled) {
    jdbc.sql(
            """
            INSERT INTO projection.participant_units
              (tenant_id, participant_id, fund_id, effective_date, units_scaled,
               last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
            SELECT tenant_id, :participantId, :fundId, :effectiveDate, :unitsScaled,
                   :transferId, last_ledger_timestamp + 1, NULL, '\\x00'::bytea
              FROM projection.participant_units
             WHERE fund_id = :fundId
             LIMIT 1
            """)
        .param("participantId", new java.math.BigDecimal(uint128()))
        .param("fundId", scenario.unitLedgerId())
        .param("effectiveDate", scenario.dealingDate())
        .param("unitsScaled", unitsScaled)
        .param("transferId", new java.math.BigDecimal(uint128()))
        .update();
  }

  private static java.math.BigInteger uint128() {
    UUID random = UUID.randomUUID();
    return new java.math.BigInteger(
        1,
        java.nio.ByteBuffer.allocate(16)
            .putLong(random.getMostSignificantBits())
            .putLong(random.getLeastSignificantBits())
            .array());
  }

  private static long driftOfPersistedBreak(ReconciliationResult result, long unitLedgerId) {
    return jdbc.sql(
            """
            SELECT drift_scaled FROM workflow.reconciliation_break
             WHERE run_id = :runId AND unit_ledger_id = :unitLedgerId
            """)
        .param("runId", result.run().runId())
        .param("unitLedgerId", unitLedgerId)
        .query(Long.class)
        .single();
  }

  /** The reconciled balance of one of this run's own cash accounts, asserted from both sides. */
  private static Money cashTie(
      ReconciliationResult result,
      dev.fondaria.common.FundId fundId,
      dev.fondaria.ledger.LedgerCodes.AccountCode code) {
    dev.fondaria.ledger.AccountId id =
        stack
            .eodJobBean(dev.fondaria.ledger.AccountIdDeriver.class)
            .derive(code, fundId.value().toString());
    var tie =
        result.cashTies().stream()
            .filter(candidate -> candidate.accountId().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(tie.ties())
        .as("%s: ledger %s, projection %s", code, tie.ledgerCash(), tie.projectionCash())
        .isTrue();
    return tie.ledgerCash();
  }
}
