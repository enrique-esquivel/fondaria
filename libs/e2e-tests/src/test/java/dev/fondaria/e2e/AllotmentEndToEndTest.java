package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 21.5's criteria:
 *
 * <ul>
 *   <li><b>Criterion 1:</b> cash → file → reconciled → validated → staged → allotted, with units
 *       readable through {@link dev.fondaria.ledger.LedgerPort#lookupAccount}.
 *   <li><b>Criterion 2:</b> a 200,000-row batch allots within the target window; duration recorded
 *       (unblocked by {@code ADR-0017}: partitioned into 49 linked chains, each fitting {@link
 *       dev.fondaria.ledger.LedgerLimits#MAX_LINKED_CHAIN_LENGTH}).
 *   <li><b>Criterion 5:</b> the whole path deterministic across ten consecutive runs.
 * </ul>
 *
 * <h2>Why every expected number here is a literal</h2>
 *
 * <p>MXN 4,500.00 at MXN 10.00 per unit is 450 units, split 300 vested and 150 unvested at the
 * plan's own 1:2 employer ratio. Those figures are written out rather than recomputed from the
 * scenario, because a determinism criterion asserted against a number the code under test also
 * derives would hold just as well if the derivation were wrong in the same way ten times. The
 * arithmetic itself is checked once, at the bottom, against {@link Money#value} — the platform's
 * one place units and a price become pesos.
 *
 * <h2>What "deterministic" means when every id is fresh</h2>
 *
 * <p>Every repetition registers its own employer, its own fund on its own unit ledger and its own
 * participant (docs/ENGINEERING-RULES.md §4 — fixtures are reused and TigerBeetle cannot be reset),
 * so the ids differ by construction and only the figures can be compared. That is the right
 * comparison: the criterion is that identical inputs produce identical units, an identical price
 * and identically reconciling pooled accounts, not that a rerun collides with the ledger it wrote
 * last time.
 *
 * <p>This class deliberately does not read the Query API. {@link ContributionToQueryEndToEndTest}
 * owns that surface; here the ledger is the surface, which is where Phase 21 puts a participant's
 * units and where every balance on the platform is ultimately read from.
 */
@Tag("integration")
class AllotmentEndToEndTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(AllotmentEndToEndTest.class);

  /** Overridable for a local run: {@code -Dallotment.largeBatchRows=2000}. */
  private static final int DEFAULT_LARGE_BATCH_ROWS = 200_000;

  /**
   * Target window for allotting a 200,000-row batch across 49 parts. Demo Beat 6 completes in ~1m
   * 42s live; 5 minutes provides a generous bound in shared test environments.
   */
  private static final Duration TARGET_WINDOW = Duration.ofMinutes(5);

  /** MXN 3,000.00 employee and MXN 1,500.00 employer — the plan's own 1:2 matching ratio. */
  private static final long EMPLOYEE_CENTS = 300_000L;

  private static final long EMPLOYER_CENTS = 150_000L;
  private static final long TOTAL_CENTS = EMPLOYEE_CENTS + EMPLOYER_CENTS;

  /** MXN 3,000.00 ÷ MXN 10.00 = 300 units; MXN 1,500.00 ÷ MXN 10.00 = 150. */
  private static final long VESTED_UNITS_SCALED = 300 * Units.SCALE;

  private static final long UNVESTED_UNITS_SCALED = 150 * Units.SCALE;

  private static final Duration PIPELINE_DRAIN_BUDGET = Duration.ofMinutes(15);

  private static PlatformStack stack;
  private static LedgerView ledger;

  @BeforeAll
  static void raiseTheStack() {
    stack = PlatformStack.getInstance();
    ledger = new LedgerView(stack);
  }

  /**
   * The 200,000-row batch posts hundreds of thousands of transfers, and this class asserts on the
   * ledger alone, so it would otherwise finish while the projection is still working through them —
   * and the next class's convergence waits would time out behind that backlog.
   */
  @AfterAll
  static void leaveThePipelineDrained() {
    stack.awaitPipelineDrained(PIPELINE_DRAIN_BUDGET);
  }

  @RepeatedTest(10)
  @DisplayName(
      "an employer's wire and file become units on the ledger, and the whole path — cash,"
          + " reconciliation, validation, staging, allotment — produces the same figures on every"
          + " one of ten consecutive runs")
  void theFullPathEndsInUnitsReadableFromTheLedger() {
    Scenario scenario =
        Scenario.contribute(stack, EMPLOYEE_CENTS, EMPLOYER_CENTS, Scenario.TEN_PESOS_PER_UNIT);

    // ---- The participant's own position, read the only way the platform reads a balance.
    assertThat(ledger.vestedUnits(scenario))
        .as("the employee's MXN 3,000.00 bought 300 units outright")
        .isEqualTo(Units.of(VESTED_UNITS_SCALED));
    assertThat(ledger.unvestedUnits(scenario))
        .as("the employer's MXN 1,500.00 bought 150 units, unvested under a five-year schedule")
        .isEqualTo(Units.of(UNVESTED_UNITS_SCALED));

    // ---- The fund: what it issued equals what its one participant holds.
    assertThat(ledger.unitsInIssue(scenario.fundId()))
        .as(
            "UNITS_IN_ISSUE is debited by exactly the sum credited to participants — this fund"
                + " was registered by this repetition and has no other holder")
        .isEqualTo(Units.of(VESTED_UNITS_SCALED + UNVESTED_UNITS_SCALED))
        .isEqualTo(ledger.unitsHeld(scenario));

    // ---- The cash: all of it arrived, and nothing was left in flight.
    assertThat(ledger.fundCash(scenario.fundId()))
        .as("every centavo of the wire entered FUND_CASH")
        .isEqualTo(Money.of(TOTAL_CENTS, CurrencyCode.MXN));
    assertThat(ledger.allotmentSuspense(scenario.fundId()))
        .as("ALLOTMENT_SUSPENSE nets to zero after the run")
        .isEqualTo(LedgerView.noCash());
    assertThat(ledger.employerCollections(scenario.organizationId()))
        .as("EMPLOYER_COLLECTIONS nets to zero once the batch is allotted")
        .isEqualTo(LedgerView.noCash());

    // ---- The one multiplication the model allows: units held, at the price they were bought at.
    assertThat(
            Money.value(ledger.unitsHeld(scenario), Nav.of(scenario.navScaled(), CurrencyCode.MXN)))
        .as("450 units at MXN 10.00 is the MXN 4,500.00 that was wired, to the centavo")
        .isEqualTo(Money.of(TOTAL_CENTS, CurrencyCode.MXN));
  }

  @Test
  @DisplayName(
      "a 200,000-row batch allots within the target window; duration is recorded, and all 49"
          + " parts reconcile in the ledger")
  void largeBatchAllotsWithinTargetWindow() {
    int rows = Integer.getInteger("allotment.largeBatchRows", DEFAULT_LARGE_BATCH_ROWS);
    Scenario.TargetFund fund = Scenario.TargetFund.fresh(stack);

    long startedAt = System.nanoTime();
    BulkContribution bulk =
        BulkContribution.contribute(
            stack, fund, rows, EMPLOYEE_CENTS, EMPLOYER_CENTS, Scenario.TEN_PESOS_PER_UNIT);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    long totalCents = (EMPLOYEE_CENTS + EMPLOYER_CENTS) * (long) rows;
    long expectedUnitsScaled = (300L + 150L) * Units.SCALE * rows;

    JdbcClient jdbc = stack.contributionApiBean(JdbcClient.class);
    long totalParts =
        jdbc.sql(
                """
                SELECT count(*) FROM workflow.contribution_batch
                 WHERE organization_id = :orgId
                """)
            .param("orgId", bulk.organizationId().value())
            .query(Long.class)
            .single();
    long allottedParts =
        jdbc.sql(
                """
                SELECT count(*) FROM workflow.contribution_batch
                 WHERE organization_id = :orgId
                   AND status = 'ALLOTTED'
                """)
            .param("orgId", bulk.organizationId().value())
            .query(Long.class)
            .single();

    LOGGER.info(
        "allotted {}-row batch across {} parts in {} ms (target window: {} ms)",
        rows,
        allottedParts,
        elapsed.toMillis(),
        TARGET_WINDOW.toMillis());

    assertThat(totalParts)
        .as("all parts of the batch are recorded in workflow.contribution_batch")
        .isEqualTo(allottedParts)
        .isGreaterThanOrEqualTo(1L);

    if (rows == DEFAULT_LARGE_BATCH_ROWS) {
      assertThat(allottedParts)
          .as("200,000 rows with 2 legs each partitions into 49 linked chains under ADR-0017")
          .isEqualTo(49L);
    }

    assertThat(elapsed)
        .as(
            "allotting %s rows took %s ms, exceeding the %s ms target window",
            rows, elapsed.toMillis(), TARGET_WINDOW.toMillis())
        .isLessThan(TARGET_WINDOW);

    // ---- The fund: what it issued equals what its participants hold.
    assertThat(ledger.unitsInIssue(bulk.fundId()))
        .as("UNITS_IN_ISSUE is debited by exactly the sum credited to participants")
        .isEqualTo(Units.of(expectedUnitsScaled));

    // ---- The cash: all of it arrived, and nothing was left in flight.
    assertThat(ledger.fundCash(bulk.fundId()))
        .as("every centavo of the wire entered FUND_CASH")
        .isEqualTo(Money.of(totalCents, CurrencyCode.MXN));

    assertThat(ledger.allotmentSuspense(bulk.fundId()))
        .as("ALLOTMENT_SUSPENSE nets to zero across all parts")
        .isEqualTo(LedgerView.noCash());

    assertThat(ledger.employerCollections(bulk.organizationId()))
        .as("EMPLOYER_COLLECTIONS nets to zero once all parts are allotted")
        .isEqualTo(LedgerView.noCash());

    // ---- The residual: every allotted part recorded its residual in workflow.allotment_residual
    long residualCount =
        jdbc.sql(
                """
                SELECT count(*) FROM workflow.allotment_residual
                 WHERE organization_id = :orgId
                """)
            .param("orgId", bulk.organizationId().value())
            .query(Long.class)
            .single();

    assertThat(residualCount)
        .as("every allotted part records its residual in workflow.allotment_residual")
        .isEqualTo(allottedParts);
  }
}
