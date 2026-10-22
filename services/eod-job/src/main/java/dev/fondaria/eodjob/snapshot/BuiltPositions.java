package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import java.util.Map;
import java.util.Objects;

/**
 * What one consistent read of the book produced: the positions file, and the prices it was valued
 * at.
 *
 * <p>The prices travel with the file because they were read inside the same transaction, and a fund
 * that had no positions still needs one to denominate its zero total in. Reading them again
 * afterwards would be a second cut of a table a correction can move (Task 20.5).
 */
public record BuiltPositions(PositionFile file, Map<FundId, SnapshotNav> navsByFund) {

  public BuiltPositions {
    Objects.requireNonNull(file, "file must not be null");
    navsByFund = Map.copyOf(navsByFund);
  }
}
