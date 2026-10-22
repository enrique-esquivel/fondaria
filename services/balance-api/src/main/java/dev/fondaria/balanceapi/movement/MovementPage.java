package dev.fondaria.balanceapi.movement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of movement history, newest first — {@code MovementPage} in {@code
 * contracts/openapi/balance-api.yaml}.
 *
 * <p>{@link #nextCursor} is present exactly when {@link #hasMore} is true, so a client's loop is
 * "while there is a cursor, ask again" and never has to reason about an empty last page.
 */
public record MovementPage(List<Movement> items, Optional<String> nextCursor) {

  public MovementPage {
    Objects.requireNonNull(items, "items must not be null");
    Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    items = List.copyOf(items);
  }

  public boolean hasMore() {
    return nextCursor.isPresent();
  }

  /** The last page of a history: whatever is left, and nowhere further to go. */
  public static MovementPage last(List<Movement> items) {
    return new MovementPage(items, Optional.empty());
  }
}
