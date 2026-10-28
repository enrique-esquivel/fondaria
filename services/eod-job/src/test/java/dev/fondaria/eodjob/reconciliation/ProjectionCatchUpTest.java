package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.eodjob.config.ProjectionCatchUpProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectionCatchUp}'s decision (ADR-0038): skip only when it can tell the projection is
 * behind and still moving; every way of being unable to tell runs the pass.
 */
class ProjectionCatchUpTest {

  private static final Instant LEDGER_HEAD = Instant.parse("2026-09-04T23:30:00.123456789Z");
  private static final long HEAD_NANOS = 1_788_564_600_123_456_789L;

  private final FakeLedger ledger = new FakeLedger();
  private final AtomicReference<ProjectionProgress.Snapshot> snapshot = new AtomicReference<>();
  private final AtomicReference<Long> askedAbout = new AtomicReference<>();

  private final ProjectionCatchUp catchUp =
      new ProjectionCatchUp(
          ledger,
          (headNanos, quiet) -> {
            askedAbout.set(headNanos);
            return snapshot.get();
          },
          new ProjectionCatchUpProperties(Duration.ofSeconds(15), Duration.ofMinutes(2)));

  @Test
  @DisplayName("the ledger head is handed to the projection as exact nanoseconds")
  void theHeadIsComparedInNanoseconds() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(progress(HEAD_NANOS, 0, Duration.ofSeconds(1)));

    catchUp.check();

    assertThat(askedAbout.get()).isEqualTo(HEAD_NANOS);
  }

  @Test
  @DisplayName("a projection that has committed the ledger head with no consumer draining runs")
  void caughtUpRuns() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(progress(HEAD_NANOS, 0, Duration.ofSeconds(1)));

    assertThat(catchUp.check().runPass()).isTrue();
  }

  @Test
  @DisplayName("a projection behind the head and still advancing is skipped, with its gap")
  void behindAndMovingIsSkipped() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(progress(HEAD_NANOS - 1_534_000_000_000L, 3, Duration.ofMillis(200)));

    ProjectionCatchUp.Decision decision = catchUp.check();

    assertThat(decision.runPass()).isFalse();
    assertThat(decision.gap()).isEqualTo(Duration.ofSeconds(1_534));
    assertThat(decision.busyConsumersBehind()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "one consumer at the head is not enough while another is still draining behind it — the"
          + " entries queues route by account and drain independently")
  void oneConsumerAtTheHeadIsNotEnough() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(progress(HEAD_NANOS, 1, Duration.ofMillis(100)));

    ProjectionCatchUp.Decision decision = catchUp.check();

    assertThat(decision.runPass()).isFalse();
    assertThat(decision.gap()).isEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("a projection behind the head that has stopped advancing runs, so its breaks show")
  void aStalledProjectionRunsAnyway() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(progress(HEAD_NANOS - 1_000_000_000L, 0, Duration.ofMinutes(2)));

    ProjectionCatchUp.Decision decision = catchUp.check();

    assertThat(decision.runPass()).isTrue();
    assertThat(decision.reason()).contains("not advanced");
  }

  @Test
  @DisplayName("an unknown ledger head runs the pass: the check never blocks what it cannot see")
  void noLedgerHeadRuns() {
    snapshot.set(progress(0L, 5, Duration.ZERO));

    assertThat(catchUp.check().runPass()).isTrue();
    assertThat(askedAbout.get()).as("no head, nothing to compare against").isNull();
  }

  @Test
  @DisplayName("a projection with no checkpoint at all runs the pass")
  void noCheckpointRuns() {
    ledger.headAt(LEDGER_HEAD);
    snapshot.set(new ProjectionProgress.Snapshot(Optional.empty(), 0, Optional.empty()));

    assertThat(catchUp.check().runPass()).isTrue();
  }

  private static ProjectionProgress.Snapshot progress(
      long highestNanos, long busyBehind, Duration sinceLastAdvance) {
    return new ProjectionProgress.Snapshot(
        Optional.of(highestNanos), busyBehind, Optional.of(sinceLastAdvance));
  }
}
