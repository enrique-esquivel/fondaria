package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.eodjob.reconciliation.RunStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an operator is paged on for custodian reconciliation (Task 7.3: a real break blocks and
 * pages; a missing statement is itself an alert). Same shape as {@code ReconciliationMetricsTest}
 * (Task 7.2): the gauge stays raised until a pass actually succeeds.
 */
class CustodianReconciliationMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CustodianReconciliationMetrics metrics =
      new CustodianReconciliationMetrics(registry);

  @Test
  @DisplayName("before any pass has run, the close is blocked")
  void startsBlocked() {
    assertThat(blocked()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a passing pass clears the gauge, even one that still tracks a timing difference")
  void aPassClearsTheGauge() {
    CustodianBreak timing = aBreak(BreakCategory.UNIT_TIMING_DIFFERENCE);
    metrics.record(result(RunStatus.PASSED, List.of(timing), 0, 1_250L));

    assertThat(blocked()).isEqualTo(0.0);
    assertThat(registry.get("eod_custodian_reconciliation_duration").timer().count()).isEqualTo(1L);
    assertThat(registry.get("eod_custodian_timing_differences_total").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("eod_custodian_breaks_total").counter().count()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("a real break raises the gauge and is counted separately from a timing difference")
  void aRealBreakRaisesTheGauge() {
    CustodianBreak real = aBreak(BreakCategory.CASH_BREAK);
    CustodianBreak timing = aBreak(BreakCategory.CASH_TIMING_DIFFERENCE);
    metrics.record(result(RunStatus.FAILED, List.of(real, timing), 1, 900L));

    assertThat(blocked()).isEqualTo(1.0);
    assertThat(registry.get("eod_custodian_breaks_total").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("eod_custodian_timing_differences_total").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("eod_custodian_breaks_escalated_total").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a missing statement is counted even when nothing else is wrong")
  void aMissingStatementIsCounted() {
    metrics.record(resultWithMissing(RunStatus.FAILED, 2, 300L));

    assertThat(blocked()).isEqualTo(1.0);
    assertThat(registry.get("eod_custodian_statements_late_total").counter().count())
        .isEqualTo(2.0);
  }

  private double blocked() {
    return registry.get("eod_custodian_reconciliation_blocked").gauge().value();
  }

  private static CustodianBreak aBreak(BreakCategory category) {
    LocalDate businessDate = LocalDate.of(2026, 9, 4);
    Instant now = Instant.parse("2026-09-04T02:00:00Z");
    return new CustodianBreak(
        UUID.randomUUID(),
        UUID.randomUUID(),
        category,
        BreakStatus.OPEN,
        -500L,
        businessDate,
        businessDate,
        Optional.empty(),
        now,
        now,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static CustodianReconciliationResult result(
      RunStatus status,
      List<CustodianBreak> breaksTouched,
      int escalatedCount,
      long durationMillis) {
    Instant startedAt = Instant.parse("2026-09-04T02:00:00Z");
    long realBreakCount =
        breaksTouched.stream().filter(b -> !b.category().isTimingDifference()).count();
    long timingCount =
        breaksTouched.stream().filter(b -> b.category().isTimingDifference()).count();
    return new CustodianReconciliationResult(
        new CustodianReconciliationRun(
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 4),
            status,
            startedAt,
            Optional.of(startedAt.plusMillis(durationMillis)),
            Optional.of(durationMillis),
            1,
            1,
            0,
            (int) realBreakCount,
            (int) timingCount),
        List.of(),
        breaksTouched,
        escalatedCount);
  }

  private static CustodianReconciliationResult resultWithMissing(
      RunStatus status, int missingCount, long durationMillis) {
    Instant startedAt = Instant.parse("2026-09-04T02:00:00Z");
    return new CustodianReconciliationResult(
        new CustodianReconciliationRun(
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 4),
            status,
            startedAt,
            Optional.of(startedAt.plusMillis(durationMillis)),
            Optional.of(durationMillis),
            1,
            0,
            missingCount,
            0,
            0),
        List.of(),
        List.of(),
        0);
  }
}
