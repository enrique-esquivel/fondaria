package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.LedgerCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an operator is paged on (Task 7.2's third criterion: drift blocks the chain <i>and
 * pages</i>).
 *
 * <p>The gauge, not the counter, is the thing to alert on, and these tests pin the property that
 * makes it so: it stays raised until a pass actually succeeds. A counter that only increments when
 * a break is found goes quiet while the break is still there.
 */
class ReconciliationMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ReconciliationMetrics metrics = new ReconciliationMetrics(registry);

  @Test
  @DisplayName("before any pass has run, the close is blocked — an unproved book is not a tied one")
  void startsBlocked() {
    assertThat(blocked()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a passing pass clears the gauge and records its duration")
  void aPassClearsTheGauge() {
    metrics.record(result(RunStatus.PASSED, List.of(), 1_250L));

    assertThat(blocked()).isEqualTo(0.0);
    assertThat(registry.get("eod_reconciliation_duration").timer().count()).isEqualTo(1L);
    assertThat(registry.get("eod_reconciliation_breaks_total").counter().count()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("a failing pass raises the gauge and counts every break it found")
  void aFailureRaisesTheGauge() {
    metrics.record(result(RunStatus.FAILED, List.of(aBreak(), aBreak()), 900L));

    assertThat(blocked()).isEqualTo(1.0);
    assertThat(registry.get("eod_reconciliation_breaks_total").counter().count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("the gauge stays raised across a later failure, and clears only on a pass")
  void theGaugeTracksTheLatestPass() {
    metrics.record(result(RunStatus.PASSED, List.of(), 100L));
    metrics.record(result(RunStatus.FAILED, List.of(aBreak()), 100L));
    assertThat(blocked()).isEqualTo(1.0);

    metrics.recordFailure();
    assertThat(blocked()).isEqualTo(1.0);

    metrics.record(result(RunStatus.PASSED, List.of(), 100L));
    assertThat(blocked()).isEqualTo(0.0);
  }

  @Test
  @DisplayName(
      "a clean pass sets every projection_drift_minor series to 0 and the alert expression evaluates false")
  void cleanPassSetsAllProjectionDriftGaugesToZero() {
    FundUnitTie fund1 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_001L,
            Units.of(900 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            10L);
    FundUnitTie fund2 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_002L,
            Units.of(500 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            5L);

    metrics.record(
        resultWithTies(RunStatus.PASSED, List.of(fund1, fund2), List.of(), List.of(), 100L));

    assertThat(driftGauge(1_001L)).isEqualTo(0.0);
    assertThat(driftGauge(1_002L)).isEqualTo(0.0);
    assertThat(driftGauge(484L)).isEqualTo(0.0);

    double sumAbs = sumAbsDrift();
    assertThat(sumAbs).isEqualTo(0.0);
    assertThat(sumAbs > 0).isFalse();
  }

  @Test
  @DisplayName(
      "an injected unit drift on one fund sets projection_drift_minor to the signed difference; others read 0; abs sums correctly")
  void unitDriftOnOneFundSetsSignedDifferenceWhileOthersZero() {
    FundUnitTie fund1 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_001L,
            Units.of(905 * Units.SCALE),
            Units.of(905 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            10L);
    FundUnitTie fund2 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_002L,
            Units.of(500 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            5L);

    ReconciliationBreak break1 =
        ReconciliationBreak.units(
            fund1.fundId(),
            1_001L,
            Derivation.LEDGER_PARTICIPANT_UNITS,
            905 * Units.SCALE,
            Derivation.PROJECTION_UNITS,
            900 * Units.SCALE);

    metrics.record(
        resultWithTies(RunStatus.FAILED, List.of(fund1, fund2), List.of(), List.of(break1), 100L));

    assertThat(driftGauge(1_001L)).isEqualTo((double) (5 * Units.SCALE));
    assertThat(driftGauge(1_002L)).isEqualTo(0.0);
    assertThat(driftGauge(484L)).isEqualTo(0.0);

    assertThat(sumAbsDrift()).isEqualTo((double) (5 * Units.SCALE));
  }

  @Test
  @DisplayName(
      "negative drift and positive drift on different funds sum abs correctly across funds")
  void negativeAndPositiveDriftSumAbsCorrectly() {
    FundUnitTie fund1 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_001L,
            Units.of(897 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            10L);
    FundUnitTie fund2 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_002L,
            Units.of(504 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            Units.of(500 * Units.SCALE),
            5L);

    metrics.record(
        resultWithTies(RunStatus.FAILED, List.of(fund1, fund2), List.of(), List.of(), 100L));

    assertThat(driftGauge(1_001L)).isEqualTo((double) (-3 * Units.SCALE));
    assertThat(driftGauge(1_002L)).isEqualTo((double) (4 * Units.SCALE));

    // sum(abs(projection_drift_minor)) = |-3| + |4| = 7
    assertThat(sumAbsDrift()).isEqualTo((double) (7 * Units.SCALE));
  }

  @Test
  @DisplayName("injected cash drift updates projection_drift_minor for ledger 484 signed")
  void cashDriftUpdatesCashGauge() {
    CashAccountTie tie =
        new CashAccountTie(
            new AccountId(1L, 1L),
            LedgerCodes.AccountCode.ROUNDING_RESIDUE,
            Money.of(500L, CurrencyCode.MXN),
            Money.of(350L, CurrencyCode.MXN));

    metrics.record(resultWithTies(RunStatus.FAILED, List.of(), List.of(tie), List.of(), 100L));

    assertThat(driftGauge(484L)).isEqualTo(150.0);
  }

  @Test
  @DisplayName("cash break without ties updates projection_drift_minor for ledger 484")
  void cashBreakWithoutTiesUpdatesCashGauge() {
    ReconciliationBreak cashBreak = ReconciliationBreak.cash("123456", 1_000L, 800L);

    metrics.record(
        resultWithTies(RunStatus.FAILED, List.of(), List.of(), List.of(cashBreak), 100L));

    assertThat(driftGauge(484L)).isEqualTo(200.0);
  }

  @Test
  @DisplayName("a pass that throws mid-way leaves the gauge at its previous value, not 0")
  void passThatThrowsLeavesPreviousGaugeValue() {
    FundUnitTie fund1 =
        new FundUnitTie(
            new FundId(UUID.randomUUID()),
            1_001L,
            Units.of(905 * Units.SCALE),
            Units.of(905 * Units.SCALE),
            Units.of(900 * Units.SCALE),
            10L);

    metrics.record(resultWithTies(RunStatus.FAILED, List.of(fund1), List.of(), List.of(), 100L));
    assertThat(driftGauge(1_001L)).isEqualTo((double) (5 * Units.SCALE));

    metrics.recordFailure();

    assertThat(driftGauge(1_001L)).isEqualTo((double) (5 * Units.SCALE));
    assertThat(blocked()).isEqualTo(1.0);
  }

  private double blocked() {
    return registry.get("eod_reconciliation_blocked").gauge().value();
  }

  private double driftGauge(long ledgerId) {
    return registry
        .get("projection_drift_minor")
        .tags("ledger_id", String.valueOf(ledgerId))
        .gauge()
        .value();
  }

  private double sumAbsDrift() {
    return registry.find("projection_drift_minor").gauges().stream()
        .mapToDouble(g -> Math.abs(g.value()))
        .sum();
  }

  private static ReconciliationBreak aBreak() {
    return ReconciliationBreak.units(
        new FundId(UUID.randomUUID()),
        1_001L,
        Derivation.LEDGER_PARTICIPANT_UNITS,
        900 * Units.SCALE,
        Derivation.PROJECTION_UNITS,
        899 * Units.SCALE);
  }

  private static ReconciliationResult result(
      RunStatus status, List<ReconciliationBreak> breaks, long durationMillis) {
    return resultWithTies(status, List.of(), List.of(), breaks, durationMillis);
  }

  private static ReconciliationResult resultWithTies(
      RunStatus status,
      List<FundUnitTie> unitTies,
      List<CashAccountTie> cashTies,
      List<ReconciliationBreak> breaks,
      long durationMillis) {
    Instant startedAt = Instant.parse("2026-09-04T02:00:00Z");
    return new ReconciliationResult(
        new ReconciliationRun(
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 3),
            status,
            startedAt,
            Optional.of(startedAt.plusMillis(durationMillis)),
            Optional.of(durationMillis),
            unitTies.size(),
            2L,
            5,
            breaks.size()),
        unitTies,
        cashTies,
        breaks);
  }
}
