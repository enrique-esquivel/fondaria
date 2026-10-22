package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.eodjob.snapshot.SnapshotRun;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snapshot gate (Task 7.5a): no {@code COMPLETED} dense snapshot of record, no sign-off. The
 * counterpart of {@code ReconciliationGate} and {@code CustodianReconciliationGate}, which the
 * snapshot (Task 7.4) had no equivalent of until sign-off needed one.
 */
class SnapshotGateTest {

  private static final LocalDate DATE = LocalDate.of(2026, 8, 31);
  private static final Instant AT = Instant.parse("2026-09-01T02:00:00Z");

  private final SignoffTestDoubles.Snapshots snapshots = new SignoffTestDoubles.Snapshots();
  private final SnapshotGate gate = new SnapshotGate(snapshots);

  @Test
  @DisplayName("no completed snapshot for the date is blocked")
  void noSnapshotBlocks() {
    assertThatThrownBy(() -> gate.requireTaken(DATE))
        .isInstanceOf(SnapshotNotTakenException.class)
        .hasMessageContaining("no dense snapshot of record");
  }

  @Test
  @DisplayName("a completed snapshot opens the gate and is returned")
  void aCompletedSnapshotOpens() {
    SnapshotRun run = SignoffTestDoubles.completedSnapshot(DATE, AT);
    snapshots.with(run);

    assertThat(gate.requireTaken(DATE).runId()).isEqualTo(run.runId());
  }
}
