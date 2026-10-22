package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.snapshot.EodHashChainVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled sweep swallows every exception and lets the meters carry the state — the same
 * posture as {@code ReconciliationRunner} and {@code SnapshotRunner}. Driven against a real {@link
 * SignoffService} wired with in-memory doubles, so what is tested is the runner's handling of the
 * outcomes the service actually produces.
 */
class SignoffRunnerTest {

  private static final Instant AT = Instant.parse("2026-08-31T23:30:00Z"); // a Mexico-City Monday
  private final BusinessCalendar calendar =
      new BusinessCalendar(ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.of());
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
          Clock.fixed(AT, ZoneOffset.UTC));

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SignoffMetrics metrics = new SignoffMetrics(registry);
  private final EodHashChainVerifier hashChainVerifier =
      new EodHashChainVerifier(snapshots, registry);
  private final SignoffRunner runner = new SignoffRunner(service, metrics, hashChainVerifier);

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
}
