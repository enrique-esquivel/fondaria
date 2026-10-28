package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.schedule.CloseWindow;
import dev.fondaria.eodjob.snapshot.EodHashChainVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled sweep swallows every exception and lets the meters carry the state — the same
 * posture as {@code ReconciliationRunner} and {@code SnapshotRunner}. Driven against a real {@link
 * SignoffService} wired with in-memory doubles, so what is tested is the runner's handling of the
 * outcomes the service actually produces.
 */
class SignoffRunnerTest {

  private static final Instant AT = Instant.parse("2026-08-31T23:30:00Z"); // Monday 17:30 CDMX
  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
  private final BusinessCalendar calendar =
      new BusinessCalendar(ZONE, LocalTime.of(18, 0), Set.of());
  private final LocalDate businessDate = calendar.businessDateOf(AT);

  private final SignoffTestDoubles.Signoffs signoffs = new SignoffTestDoubles.Signoffs();
  private final SignoffTestDoubles.Snapshots snapshots = new SignoffTestDoubles.Snapshots();
  private final SignoffTestDoubles.InternalRuns internalRuns =
      new SignoffTestDoubles.InternalRuns();
  private final SignoffTestDoubles.CustodianRuns custodianRuns =
      new SignoffTestDoubles.CustodianRuns();
  private final SignoffTestDoubles.RecordingPublisher publisher =
      new SignoffTestDoubles.RecordingPublisher();

  private final SignoffService service =
      new SignoffService(
          signoffs,
          new ReconciliationGate(internalRuns),
          new CustodianReconciliationGate(custodianRuns),
          new SnapshotGate(snapshots),
          publisher,
          calendar,
          CLOCK);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SignoffMetrics metrics = new SignoffMetrics(registry);
  private final EodHashChainVerifier hashChainVerifier =
      new EodHashChainVerifier(snapshots, registry);
  private final SignoffRunner runner =
      new SignoffRunner(
          service,
          metrics,
          hashChainVerifier,
          CloseWindow.always(ZONE, calendar, CLOCK, signoffs::latestSignedOffDate));

  private SignoffRunner runnerWithWindowAt(LocalTime start) {
    return new SignoffRunner(
        service,
        metrics,
        hashChainVerifier,
        CloseWindow.openingAt(start, ZONE, calendar, CLOCK, signoffs::latestSignedOffDate));
  }

  private void allThreeGatesOpen() {
    internalRuns.with(SignoffTestDoubles.passedInternalRun(businessDate, AT));
    custodianRuns.with(SignoffTestDoubles.passedCustodianRun(businessDate, AT));
    snapshots.with(SignoffTestDoubles.completedSnapshot(businessDate, AT));
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  @Test
  @DisplayName("a successful sweep records the result and clears blocked")
  void successRecords() {
    allThreeGatesOpen();

    runner.sweep();

    assertThat(gauge("eod_signoff_blocked")).isEqualTo(0.0);
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(0.0);
    assertThat(registry.get("eod_signoff_total").counter().count()).isEqualTo(1.0);
    assertThat(publisher.published()).hasSize(1);
  }

  @Test
  @DisplayName("a blocked gate is swallowed and leaves blocked raised")
  void blockedGateIsSwallowed() {
    assertThatCode(runner::sweep).doesNotThrowAnyException();
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(1.0);
    assertThat(signoffs.find(businessDate)).isEmpty();
  }

  @Test
  @DisplayName("a publish failure does not throw: the row is written, announce_pending is raised")
  void aPublishFailureIsSwallowed() {
    allThreeGatesOpen();
    publisher.failNext(1);

    assertThatCode(runner::sweep).doesNotThrowAnyException();

    assertThat(gauge("eod_signoff_blocked")).isEqualTo(0.0);
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(1.0);
    assertThat(signoffs.find(businessDate).orElseThrow().isAnnounced()).isFalse();

    // The next sweep re-announces it and the gauge falls back.
    runner.sweep();
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(0.0);
    assertThat(signoffs.find(businessDate).orElseThrow().isAnnounced()).isTrue();
  }

  @Test
  @DisplayName("before the close window opens, today is not signed off and nothing is recorded")
  void beforeTheWindowTodayIsLeftAlone() {
    allThreeGatesOpen();

    runnerWithWindowAt(LocalTime.of(17, 45)).sweep();

    assertThat(signoffs.find(businessDate)).isEmpty();
    assertThat(publisher.published()).isEmpty();
    assertThat(registry.get("eod_signoff_total").counter().count()).isZero();
    // Neither a pass nor a block: the gauge keeps its starting value.
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("before the close window opens, a stranded sign-off is still re-announced")
  void beforeTheWindowAStrandedSignoffIsStillReannounced() {
    LocalDate yesterday = businessDate.minusDays(3);
    signoffs.insertIfAbsent(
        new CloseSignoff(
            yesterday,
            AT.minusSeconds(86_400),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            "snapshots/dt=" + yesterday + "/positions.ndjson.gz",
            "snapshots/dt=" + yesterday + "/manifest.json",
            Optional.empty()));

    runnerWithWindowAt(LocalTime.of(17, 45)).sweep();

    assertThat(publisher.published()).hasSize(1);
    assertThat(signoffs.find(yesterday).orElseThrow().isAnnounced()).isTrue();
    assertThat(gauge("eod_signoff_announce_pending")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("once the close window is open, the sweep signs off as it does with no window")
  void insideTheWindowTheSweepSignsOff() {
    allThreeGatesOpen();

    runnerWithWindowAt(LocalTime.of(17, 0)).sweep();

    assertThat(signoffs.find(businessDate)).isPresent();
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(0.0);
    assertThat(publisher.published()).hasSize(1);
  }

  // ---- a date still unsigned after its cut-off -----------------------------------------------

  /** A sweep at {@code at}, with a 17:00 window, against the same doubles. */
  private void sweepAt(Instant at) {
    Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    SignoffService late =
        new SignoffService(
            signoffs,
            new ReconciliationGate(internalRuns),
            new CustodianReconciliationGate(custodianRuns),
            new SnapshotGate(snapshots),
            publisher,
            calendar,
            clock);
    new SignoffRunner(
            late,
            metrics,
            hashChainVerifier,
            CloseWindow.openingAt(
                LocalTime.of(17, 0), ZONE, calendar, clock, signoffs::latestSignedOffDate))
        .sweep();
  }

  private void signedOff(LocalDate date) {
    signoffs.put(
        new CloseSignoff(
            date,
            AT.minusSeconds(3 * 86_400),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            "snapshots/dt=" + date + "/positions.ndjson.gz",
            "snapshots/dt=" + date + "/manifest.json",
            Optional.of(AT.minusSeconds(3 * 86_400))));
  }

  @Test
  @DisplayName("a date whose gates open only after cut-off is signed off — not the next date")
  void aDateThatTiesAfterCutoffIsSignedOff() {
    // The 2026-10-27 load run: breaks at 17:58 only because the projection was still draining,
    // tied at 18:03, and nothing ever signed it off.
    LocalDate friday = LocalDate.of(2026, 8, 28);
    signedOff(friday);
    Instant justBeforeCutoff = Instant.parse("2026-08-31T23:58:00Z"); // Monday 17:58 CDMX
    Instant justAfterCutoff = Instant.parse("2026-09-01T00:03:00Z"); // Monday 18:03 CDMX
    internalRuns.with(SignoffTestDoubles.failedInternalRun(businessDate, justBeforeCutoff));
    sweepAt(justBeforeCutoff);
    assertThat(signoffs.find(businessDate)).isEmpty();

    // The projection catches up after cut-off: reconciliation ties and the snapshot is taken.
    allThreeGatesOpen();
    assertThat(calendar.businessDateOf(justAfterCutoff))
        .as("the clock now falls in the next business date")
        .isEqualTo(LocalDate.of(2026, 9, 1));

    sweepAt(justAfterCutoff);

    assertThat(signoffs.find(businessDate))
        .as("Monday is signed off after its cut-off")
        .isPresent();
    assertThat(signoffs.find(LocalDate.of(2026, 9, 1))).isEmpty();
    CloseSignedOffEvent event = publisher.onlyPublished();
    assertThat(event.businessDate()).isEqualTo(businessDate);
    assertThat(event.lastBusinessDayOfMonth())
        .as("a late month-end sign-off still opens the monthly statements")
        .isTrue();
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(0.0);
    assertThat(gauge("eod_close_dates_behind")).isZero();
  }

  @Test
  @DisplayName(
      "while a past date is blocked, the next date is not signed off even with its gates open")
  void theNextDateWaitsBehindAnUnsignedOne() {
    LocalDate friday = LocalDate.of(2026, 8, 28);
    LocalDate tuesday = LocalDate.of(2026, 9, 1);
    signedOff(friday);
    Instant tuesdayClose = Instant.parse("2026-09-01T23:30:00Z"); // Tuesday 17:30 CDMX
    internalRuns.with(SignoffTestDoubles.passedInternalRun(tuesday, tuesdayClose));
    custodianRuns.with(SignoffTestDoubles.passedCustodianRun(tuesday, tuesdayClose));
    snapshots.with(SignoffTestDoubles.completedSnapshot(tuesday, tuesdayClose));

    sweepAt(tuesdayClose);

    assertThat(signoffs.find(tuesday)).isEmpty();
    assertThat(signoffs.find(businessDate)).isEmpty();
    assertThat(publisher.published()).isEmpty();
    assertThat(gauge("eod_signoff_blocked")).isEqualTo(1.0);
    assertThat(gauge("eod_close_dates_behind")).isEqualTo(1.0);
  }
}
