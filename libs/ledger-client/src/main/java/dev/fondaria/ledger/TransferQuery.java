package dev.fondaria.ledger;

import java.util.Objects;
import java.util.Optional;

/** A page of {@link LedgerPort#accountTransfers} history, ordered by ledger timestamp. */
public record TransferQuery(int limit, Optional<TransferId> after, SortDirection direction) {

  public TransferQuery {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, got: " + limit);
    }
    Objects.requireNonNull(after, "after must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
  }

  /** The first page, most recent transfer first. */
  public static TransferQuery firstPage(int limit) {
    return new TransferQuery(limit, Optional.empty(), SortDirection.DESCENDING);
  }

  /** The page following {@code cursor}, an opaque id from a previously returned transfer. */
  public static TransferQuery after(TransferId cursor, int limit, SortDirection direction) {
    Objects.requireNonNull(cursor, "cursor must not be null");
    return new TransferQuery(limit, Optional.of(cursor), direction);
  }

  public enum SortDirection {
    ASCENDING,
    DESCENDING
  }
}
