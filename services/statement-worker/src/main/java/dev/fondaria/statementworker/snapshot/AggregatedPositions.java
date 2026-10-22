package dev.fondaria.statementworker.snapshot;

import dev.fondaria.common.Money;
import dev.fondaria.statementworker.ParticipantKey;
import java.util.Map;
import java.util.Objects;

/**
 * One snapshot file's positions, summed to the grain a statement is rendered at (Task 7.5b): a
 * participant's total value across every fund they hold, plus how many position rows the file
 * actually carried.
 *
 * @param valueByParticipant {@code Σ Money.value(units, nav)} per {@link ParticipantKey} —
 *     <b>not</b> re-derived here; each addend is read straight off the file, which already carries
 *     {@code valueMinorUnits} computed once by Task 7.4's {@code SnapshotPosition.of}.
 * @param positionCount every row read, before grouping — compared against {@code
 *     workflow.snapshot_run.position_count} (carried on {@code close.signed-off.v1} for the closing
 *     side) as the file's own integrity check, independent of anything this service computes.
 */
public record AggregatedPositions(
    Map<ParticipantKey, Money> valueByParticipant, long positionCount) {

  public AggregatedPositions {
    Objects.requireNonNull(valueByParticipant, "valueByParticipant must not be null");
    valueByParticipant = Map.copyOf(valueByParticipant);
  }
}
