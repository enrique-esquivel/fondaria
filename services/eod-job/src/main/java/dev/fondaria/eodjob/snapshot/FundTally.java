package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * What one fund's rows added up to <i>inside the file</i>, accumulated as {@link
 * PositionFileWriter} streams them.
 *
 * <p>Deliberately one side of the tie and not both. The ledger's {@code units_in_issue} is read
 * separately, by {@code SnapshotService}, and the two are only brought together in {@link
 * SnapshotFundTotal}. Keeping them apart until then is what stops the file's own total from ever
 * being sourced from the number it is supposed to be checked against.
 */
public record FundTally(
    FundId fundId, long unitLedgerId, long positionCount, Units units, Money value) {

  public FundTally {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(value, "value must not be null");
  }

  static FundTally opening(FundId fundId, long unitLedgerId, Money zero) {
    return new FundTally(fundId, unitLedgerId, 0L, Units.of(0L), zero);
  }

  /** Adds one position's units and value. Overflow-checked by {@code Units} and {@code Money}. */
  FundTally plus(SnapshotPosition position) {
    return new FundTally(
        fundId,
        unitLedgerId,
        Math.addExact(positionCount, 1L),
        units.plus(position.units()),
        value.plus(position.value()));
  }
}
