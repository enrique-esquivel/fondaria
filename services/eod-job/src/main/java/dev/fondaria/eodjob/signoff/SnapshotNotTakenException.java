package dev.fondaria.eodjob.signoff;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Sign-off (Task 7.5a) was asked to run for a date with no {@code COMPLETED} dense snapshot (Task
 * 7.4).
 *
 * <p>The third of the three upstream gates, and the one with no gate class of its own before this
 * task — {@code ReconciliationGate} and {@code CustodianReconciliationGate} exist; {@link
 * SnapshotGate} is added here. Same posture: fails closed, carries the date and not the reason
 * (which is in {@code workflow.snapshot_run.failure_reason}).
 */
public final class SnapshotNotTakenException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final LocalDate businessDate;

  public SnapshotNotTakenException(LocalDate businessDate) {
    super("no dense snapshot of record for " + businessDate);
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
  }

  public LocalDate businessDate() {
    return businessDate;
  }
}
