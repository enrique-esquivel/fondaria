package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The signal an alert rule reads, mirroring {@code ReconciliationMetricsTest} for Task 7.2. */
class SnapshotMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SnapshotMetrics metrics = new SnapshotMetrics(registry);

  @Test
  @DisplayName("starts raised — before the first pass nothing has proved a snapshot exists")
  void startsRaised() {
    assertThat(missing()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a stored snapshot clears the gauge and records its size and duration")
  void aStoredSnapshotClearsTheGauge() {
    metrics.record(result(1_000L, 2_500L));

    assertThat(missing()).isZero();
    assertThat(registry.get("eod_snapshot_positions").gauge().value()).isEqualTo(1_000.0);
    assertThat(registry.get("eod_snapshot_duration").timer().count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("a sweep that found the date already taken clears the gauge and times nothing")
  void alreadyTakenClearsWithoutTiming() {
    metrics.recordAlreadyTaken();

    assertThat(missing()).isZero();
    assertThat(registry.get("eod_snapshot_duration").timer().count())
        .as("a pass that did no work must not make the close's timing history look faster")
        .isZero();
  }

  @Test
  @DisplayName("a failure raises the gauge again, and it stays raised")
  void aFailureRaisesTheGaugeAndKeepsItRaised() {
    metrics.record(result(1_000L, 2_500L));
    metrics.recordFailure();

    assertThat(missing())
        .as("a gauge stays raised until a pass succeeds, where a counter would go quiet")
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "untied funds are counted, so a rate distinguishes one break from a book coming apart")
  void untiedFundsAreCounted() {
    metrics.recordUntied(2);
    metrics.recordUntied(1);

    assertThat(registry.get("eod_snapshot_untied_total").counter().count()).isEqualTo(3.0);
  }

  @Test
  @DisplayName("carries no participant, organization or fund label on any meter")
  void carriesNoIdentifyingLabels() {
    metrics.record(result(1L, 1L));
    metrics.recordUntied(1);

    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .as(
                        "a label is as much a log line as a message is (docs/ENGINEERING-RULES.md §7)")
                    .isEmpty());
  }

  private double missing() {
    return registry.get("eod_snapshot_missing").gauge().value();
  }

  private static SnapshotResult result(long positionCount, long durationMillis) {
    LocalDate date = LocalDate.of(2026, 3, 2);
    return new SnapshotResult(
        new SnapshotRun(
            UUID.randomUUID(),
            date,
            SnapshotStatus.COMPLETED,
            Instant.EPOCH,
            Optional.of(Instant.EPOCH.plusMillis(durationMillis)),
            Optional.of(durationMillis),
            positionCount,
            1,
            Optional.of("snapshots/dt=2026-03-02/positions.ndjson.gz"),
            Optional.of("a".repeat(64)),
            Optional.of("snapshots/dt=2026-03-02/manifest.json"),
            Optional.of("b".repeat(64)),
            Optional.empty(),
            Optional.empty()),
        List.of(
            new SnapshotFundTotal(
                new FundId(UUID.randomUUID()),
                1001L,
                positionCount,
                Units.of(1L),
                Money.of(1L, CurrencyCode.MXN),
                Units.of(1L))),
        Optional.empty(),
        true);
  }
}
