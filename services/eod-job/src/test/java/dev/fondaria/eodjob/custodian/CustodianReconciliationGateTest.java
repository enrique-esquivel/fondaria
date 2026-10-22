package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.eodjob.reconciliation.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate Task 7.5's sign-off calls before it reads custodian figures. Same fail-closed shape as
 * {@code ReconciliationGateTest} (Task 7.2): the only input that opens it is a settled pass that
 * found nothing wrong.
 */
class CustodianReconciliationGateTest {

  private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 1);
  private static final Instant MORNING = Instant.parse("2026-09-01T14:00:00Z");

  private final InMemoryCustodianDirectories.Runs runs = new InMemoryCustodianDirectories.Runs();
  private final CustodianReconciliationGate gate = new CustodianReconciliationGate(runs);

  @Test
  @DisplayName("a date nothing has reconciled is blocked")
  void noRunBlocks() {
    assertThat(gate.isOpen(TUESDAY)).isFalse();
    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CustodianCloseBlockedException.class)
        .hasMessageContaining("has not run");
  }

  @Test
  @DisplayName("a pass still running is blocked")
  void anUnfinishedRunBlocks() {
    runs.open(UUID.randomUUID(), TUESDAY, MORNING);

    assertThat(gate.isOpen(TUESDAY)).isFalse();
    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CustodianCloseBlockedException.class)
        .hasMessageContaining("has not finished");
  }

  @Test
  @DisplayName("a pass that found a real break, or a missing statement, is blocked")
  void aFailedRunBlocks() {
    settle(RunStatus.FAILED, 2, 1);

    assertThatThrownBy(() -> gate.requirePassed(TUESDAY))
        .isInstanceOf(CustodianCloseBlockedException.class)
        .hasMessageContaining("2 real break(s)")
        .hasMessageContaining("1 missing statement(s)");
  }

  @Test
  @DisplayName("a pass that tied opens the gate, even with a tracked timing difference")
  void aPassedRunOpens() {
    settle(RunStatus.PASSED, 0, 0);

    assertThat(gate.isOpen(TUESDAY)).isTrue();
    assertThatCode(() -> gate.requirePassed(TUESDAY)).doesNotThrowAnyException();
  }

  private void settle(RunStatus status, int realBreakCount, int missingStatementCount) {
    UUID runId = UUID.randomUUID();
    runs.open(runId, TUESDAY, MORNING);
    Instant completedAt = MORNING.plusSeconds(30);
    runs.complete(
        new CustodianReconciliationRun(
            runId,
            TUESDAY,
            status,
            MORNING,
            Optional.of(completedAt),
            Optional.of(Duration.between(MORNING, completedAt).toMillis()),
            1,
            1,
            missingStatementCount,
            realBreakCount,
            0));
  }
}
