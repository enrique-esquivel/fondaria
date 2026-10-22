package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate that makes drift block the rest of the daily close (Task 7.2's third criterion).
 *
 * <p>Every case here is a way of <b>failing closed</b>. That is the whole design: the only input
 * that opens the gate is a completed pass that found nothing, and every other state — including
 * states that look like nothing happened — blocks. A gate that opened on absence would let a close
 * proceed on the strength of a reconciliation that never ran.
 */
class ReconciliationGateTest {

  private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 1);
  private static final Instant MORNING = Instant.parse("2026-09-01T14:00:00Z");
  private static final Instant EVENING = Instant.parse("2026-09-01T23:00:00Z");

  private final InMemoryDirectories.Runs runs = new InMemoryDirectories.Runs();
  private final ReconciliationGate gate = new ReconciliationGate(runs);

  @Test
  @DisplayName("a date nothing has reconciled is blocked")
  void noRunBlocks() {
    assertThat(gate.isOpen(TUESDAY)).isFalse();
    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CloseBlockedException.class)
        .hasMessageContaining("has not run");
  }

  @Test
  @DisplayName("a pass still running is blocked — a process killed mid-pass leaves exactly this")
  void anUnfinishedRunBlocks() {
    runs.open(UUID.randomUUID(), TUESDAY, MORNING);

    assertThat(gate.isOpen(TUESDAY)).isFalse();
    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CloseBlockedException.class)
        .hasMessageContaining("has not finished");
  }

  @Test
  @DisplayName("a pass that found drift is blocked, and the message says how much it found")
  void aFailedRunBlocks() {
    settle(UUID.randomUUID(), MORNING, RunStatus.FAILED, 3);

    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CloseBlockedException.class)
        .hasMessageContaining("3 break(s)");
  }

  @Test
  @DisplayName("a pass that tied opens the gate")
  void aPassedRunOpens() {
    settle(UUID.randomUUID(), MORNING, RunStatus.PASSED, 0);

    assertThat(gate.isOpen(TUESDAY)).isTrue();
    assertThatCode(() -> gate.requirePassed(TUESDAY)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "only the latest pass counts: this morning's pass does not authorise tonight's close")
  void theLatestRunWins() {
    settle(UUID.randomUUID(), MORNING, RunStatus.PASSED, 0);
    settle(UUID.randomUUID(), EVENING, RunStatus.FAILED, 1);

    assertThat(gate.isOpen(TUESDAY)).isFalse();
  }

  @Test
  @DisplayName(
      "a slow pass that started first and found drift outranks a quick one that started second"
          + " and passed")
  void theRunThatSettledLastWins() {
    // Two instances on one date -- a redeploy overlap, or a replica nobody meant to scale. A
    // starts first and is slow; B starts second, finishes quickly and passes; A finishes
    // afterwards having found genuine drift. Ordered by START time, B is latest forever and A's
    // finding never reaches the gate at all: the close proceeds on a book with a detected break
    // in it. Ordered by SETTLE time, A's finding is the newer evidence the moment it lands.
    UUID slowAndFirst = UUID.randomUUID();
    UUID quickAndSecond = UUID.randomUUID();
    runs.open(slowAndFirst, TUESDAY, MORNING);
    runs.open(quickAndSecond, TUESDAY, MORNING.plusSeconds(60));

    runs.complete(
        settledRun(
            quickAndSecond, MORNING.plusSeconds(60), MORNING.plusSeconds(70), RunStatus.PASSED, 0),
        List.of());
    assertThat(gate.isOpen(TUESDAY))
        .as("while the slow pass is still running, the only evidence there is says the book ties")
        .isTrue();

    runs.complete(
        settledRun(slowAndFirst, MORNING, MORNING.plusSeconds(600), RunStatus.FAILED, 1),
        List.of());

    assertThat(gate.isOpen(TUESDAY))
        .as("the drift the slow pass found is the newest evidence, whenever it started")
        .isFalse();
    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CloseBlockedException.class)
        .hasMessageContaining("1 break(s)");
  }

  @Test
  @DisplayName(
      "a run left RUNNING by a dead process blocks its date until something settles, not forever")
  void anAbandonedRunDoesNotBlockTheDateForever() {
    UUID abandoned = UUID.randomUUID();
    runs.open(abandoned, TUESDAY, MORNING);
    assertThat(gate.isOpen(TUESDAY))
        .as("nothing has settled, so the unfinished row is the latest evidence there is")
        .isFalse();

    UUID rerun = UUID.randomUUID();
    runs.open(rerun, TUESDAY, EVENING);
    runs.complete(
        settledRun(rerun, EVENING, EVENING.plusSeconds(30), RunStatus.PASSED, 0), List.of());

    assertThat(gate.isOpen(TUESDAY))
        .as(
            "an abandoned row that no later run could ever displace would need a manual UPDATE to"
                + " reopen the close; sorting it behind every settled run is what avoids that")
        .isTrue();
  }

  @Test
  @DisplayName("a break found for one date does not block a different date's close")
  void breaksAreScopedToTheirDate() {
    settle(UUID.randomUUID(), MORNING, RunStatus.FAILED, 1);

    assertThat(gate.isOpen(TUESDAY.plusDays(1))).isFalse();
    settleOn(TUESDAY.plusDays(1), UUID.randomUUID(), EVENING, RunStatus.PASSED, 0);
    assertThat(gate.isOpen(TUESDAY.plusDays(1))).isTrue();
    assertThat(gate.isOpen(TUESDAY)).isFalse();
  }

  private void settle(UUID runId, Instant startedAt, RunStatus status, int breakCount) {
    settleOn(TUESDAY, runId, startedAt, status, breakCount);
  }

  private void settleOn(
      LocalDate businessDate, UUID runId, Instant startedAt, RunStatus status, int breakCount) {
    runs.open(runId, businessDate, startedAt);
    runs.complete(
        settledRun(businessDate, runId, startedAt, startedAt.plusSeconds(30), status, breakCount),
        List.of());
  }

  private static ReconciliationRun settledRun(
      UUID runId, Instant startedAt, Instant completedAt, RunStatus status, int breakCount) {
    return settledRun(TUESDAY, runId, startedAt, completedAt, status, breakCount);
  }

  private static ReconciliationRun settledRun(
      LocalDate businessDate,
      UUID runId,
      Instant startedAt,
      Instant completedAt,
      RunStatus status,
      int breakCount) {
    return new ReconciliationRun(
        runId,
        businessDate,
        status,
        startedAt,
        Optional.of(completedAt),
        Optional.of(Duration.between(startedAt, completedAt).toMillis()),
        1,
        2L,
        5,
        breakCount);
  }
}
