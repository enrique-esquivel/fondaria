package dev.fondaria.statementworker.snapshot;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The month-end snapshot of the <b>prior</b> month — Task 7.5b's "opening" side of the waterfall
 * (ADR-0022 §4). Read from {@code workflow.snapshot_run} directly by {@link
 * OpeningSnapshotRepository}, the same table Task 7.4 owns; {@code statement-worker} depends on no
 * other service's source tree (the same duplication {@code SignoffGate}'s javadoc names for {@code
 * FundDirectory}).
 *
 * @param businessDate the prior month's last business day
 * @param positionsObjectKey the object-storage key of that day's positions file, or empty if no
 *     snapshot has ever completed before the period being statemented — the genesis case, in which
 *     every participant's opening balance is zero
 */
public record OpeningSnapshot(LocalDate businessDate, String positionsObjectKey) {

  public OpeningSnapshot {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(positionsObjectKey, "positionsObjectKey must not be null");
  }
}
