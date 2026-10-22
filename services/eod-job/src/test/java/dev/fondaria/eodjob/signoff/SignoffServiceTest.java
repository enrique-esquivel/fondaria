package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.eodjob.custodian.CustodianCloseBlockedException;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.custodian.CustodianReconciliationRun;
import dev.fondaria.eodjob.reconciliation.CloseBlockedException;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.reconciliation.ReconciliationRun;
import dev.fondaria.eodjob.snapshot.SnapshotRun;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sign-off (Task 7.5a) — the fourth gate, and the first acceptance criterion of Task 7.5: "any
 * failed gate blocks sign-off and blocks statements". Every blocked case here is a way of failing
 * closed; the one open case writes the row and publishes the event.
 */
class SignoffServiceTest {

  private static final LocalDate MONTH_END = LocalDate.of(2026, 8, 31); // Monday, last business day
  private static final LocalDate MID_MONTH = LocalDate.of(2026, 8, 14); // Friday
  private static final Instant AT = Instant.parse("2026-09-01T02:15:30.123456Z");

  private final BusinessCalendar calendar =
      new BusinessCalendar(ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.of());

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

  private ReconciliationRun internalRun;
  private CustodianReconciliationRun custodianRun;
  private SnapshotRun snapshotRun;

  private void allThreeGatesOpen(LocalDate date) {
    internalRun = SignoffTestDoubles.passedInternalRun(date, AT);
    custodianRun = SignoffTestDoubles.passedCustodianRun(date, AT);
    snapshotRun = SignoffTestDoubles.completedSnapshot(date, AT);
    internalRuns.with(internalRun);
    custodianRuns.with(custodianRun);
    snapshots.with(snapshotRun);
  }

  @Test
  @DisplayName(
      "all three gates open: the row is written, the event is published, the validated run ids"
          + " recorded")
  void signsOffWhenEveryGateIsOpen() {
    allThreeGatesOpen(MONTH_END);

    SignoffResult result = service.signOffFor(MONTH_END);

    assertThat(result.newlySignedOff()).isTrue();
    assertThat(result.announced()).isTrue();

    CloseSignoff row = signoffs.find(MONTH_END).orElseThrow();
    assertThat(row.reconciliationRunId()).isEqualTo(internalRun.runId());
    assertThat(row.custodianRunId()).isEqualTo(custodianRun.runId());
    assertThat(row.snapshotRunId()).isEqualTo(snapshotRun.runId());
    assertThat(row.snapshotPositionCount()).isEqualTo(snapshotRun.positionCount());
    assertThat(row.signedOffAt()).isEqualTo(AT);
    assertThat(row.isAnnounced()).isTrue();

    CloseSignedOffEvent event = publisher.onlyPublished();
    assertThat(event.businessDate()).isEqualTo(MONTH_END);
    assertThat(event.key()).isEqualTo("2026-08-31");
    assertThat(event.snapshotObjectKey()).isEqualTo(row.snapshotObjectKey());
    assertThat(event.snapshotManifestKey()).isEqualTo(row.snapshotManifestKey());
    assertThat(event.snapshotPositionCount()).isEqualTo(snapshotRun.positionCount());
    assertThat(event.lastBusinessDayOfMonth())
        .as("2026-08-31 is a Monday and the last business day of August")
        .isTrue();
  }

  @Test
  @DisplayName("the recorded run id is the one the gate validated, not a re-read")
  void theRecordedRunIsTheOneTheGateReturned() {
    allThreeGatesOpen(MONTH_END);
    // A later FAILED reconciliation settles between "gate passed" and (in the old design) a
    // re-read.
    // The gate returned the PASSED run, so that is what the row must name — not this.
    custodianRuns.with(SignoffTestDoubles.passedCustodianRun(MONTH_END, AT)); // unchanged
    ReconciliationRun stillTheOne = internalRun;

    service.signOffFor(MONTH_END);
    internalRuns.with(SignoffTestDoubles.failedInternalRun(MONTH_END, AT.plusSeconds(60)));

    assertThat(signoffs.find(MONTH_END).orElseThrow().reconciliationRunId())
        .isEqualTo(stillTheOne.runId());
  }

  @Test
  @DisplayName("a mid-month sign-off carries lastBusinessDayOfMonth=false")
  void midMonthEventIsNotAMonthEnd() {
    allThreeGatesOpen(MID_MONTH);

    service.signOffFor(MID_MONTH);

    assertThat(publisher.onlyPublished().lastBusinessDayOfMonth()).isFalse();
  }

  @Test
  @DisplayName("internal reconciliation not passed: blocked, no row, no event")
  void blockedWhenInternalReconciliationHasNotPassed() {
    custodianRuns.with(SignoffTestDoubles.passedCustodianRun(MONTH_END, AT));
    snapshots.with(SignoffTestDoubles.completedSnapshot(MONTH_END, AT));

    assertThatThrownBy(() -> service.signOffFor(MONTH_END))
        .isInstanceOf(CloseBlockedException.class);
    assertThat(signoffs.find(MONTH_END)).isEmpty();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  @DisplayName("custodian reconciliation not passed: blocked, no row, no event")
  void blockedWhenCustodianReconciliationHasNotPassed() {
    internalRuns.with(SignoffTestDoubles.passedInternalRun(MONTH_END, AT));
    snapshots.with(SignoffTestDoubles.completedSnapshot(MONTH_END, AT));

    assertThatThrownBy(() -> service.signOffFor(MONTH_END))
        .isInstanceOf(CustodianCloseBlockedException.class);
    assertThat(signoffs.find(MONTH_END)).isEmpty();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  @DisplayName("no dense snapshot of record: blocked, no row, no event")
  void blockedWhenNoSnapshotOfRecord() {
    internalRuns.with(SignoffTestDoubles.passedInternalRun(MONTH_END, AT));
    custodianRuns.with(SignoffTestDoubles.passedCustodianRun(MONTH_END, AT));

    assertThatThrownBy(() -> service.signOffFor(MONTH_END))
        .isInstanceOf(SnapshotNotTakenException.class);
    assertThat(signoffs.find(MONTH_END)).isEmpty();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  @DisplayName("a date already signed off and announced: a no-op, nothing re-published")
  void alreadySignedOffIsANoOp() {
    allThreeGatesOpen(MONTH_END);
    service.signOffFor(MONTH_END);

    SignoffResult again = service.signOffFor(MONTH_END);

    assertThat(again.newlySignedOff()).isFalse();
    assertThat(again.announced()).isTrue();
    assertThat(publisher.published()).hasSize(1);
  }

  @Test
  @DisplayName("publish fails: the row is written, announced=false, the next sweep re-publishes")
  void aFailedPublishIsRetriedOnTheNextSweep() {
    allThreeGatesOpen(MONTH_END);
    publisher.failNext(1);

    SignoffResult first = service.signOffFor(MONTH_END);
    assertThat(first.newlySignedOff()).isTrue();
    assertThat(first.announced()).isFalse();
    assertThat(signoffs.find(MONTH_END).orElseThrow().isAnnounced()).isFalse();
    assertThat(publisher.published()).isEmpty();

    SignoffResult second = service.signOffFor(MONTH_END);
    assertThat(second.newlySignedOff()).isFalse();
    assertThat(second.announced()).isTrue();
    assertThat(publisher.published()).hasSize(1);
    assertThat(signoffs.find(MONTH_END).orElseThrow().isAnnounced()).isTrue();
  }

  @Test
  @DisplayName("signOff() re-announces a stranded sign-off even when today's close is blocked")
  void strandedPendingIsReannouncedRegardlessOfToday() {
    // A prior date signed off but its event never went out — a broker outage that outlasted the
    // cut-off. Today (the clock's business date, 2026-09-01) has no reconciliation at all.
    signoffs.put(
        new CloseSignoff(
            LocalDate.of(2026, 7, 31),
            AT.minusSeconds(200_000),
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            123L,
            "snapshots/dt=2026-07-31/positions.ndjson.gz",
            "snapshots/dt=2026-07-31/manifest.json",
            Optional.empty()));

    assertThatThrownBy(service::signOff).isInstanceOf(CloseBlockedException.class);

    assertThat(publisher.published()).hasSize(1);
    assertThat(publisher.published().get(0).businessDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    assertThat(signoffs.find(LocalDate.of(2026, 7, 31)).orElseThrow().isAnnounced()).isTrue();
  }

  @Test
  @DisplayName("signing off is idempotent: two passes for one date write one row")
  void twoPassesWriteOneRow() {
    allThreeGatesOpen(MONTH_END);

    assertThatCode(
            () -> {
              service.signOffFor(MONTH_END);
              service.signOffFor(MONTH_END);
            })
        .doesNotThrowAnyException();

    assertThat(publisher.published()).hasSize(1);
  }
}
