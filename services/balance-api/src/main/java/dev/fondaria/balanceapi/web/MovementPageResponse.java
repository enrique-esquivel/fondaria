package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.movement.MovementPage;
import java.util.List;

/**
 * One page of movement history — {@code MovementPage} in {@code
 * contracts/openapi/balance-api.yaml}.
 *
 * <p>{@code nextCursor} is {@code null} exactly when {@code hasMore} is {@code false}. Both are on
 * the response although either would do: {@code hasMore} is what a client branches on and reads
 * plainly, and the cursor is what it sends back. Deriving one from the other in every client is how
 * "null means done" gets misread once.
 */
public record MovementPageResponse(
    List<MovementResponse> items, String nextCursor, boolean hasMore) {

  public static MovementPageResponse from(MovementPage page) {
    return new MovementPageResponse(
        page.items().stream().map(MovementResponse::from).toList(),
        page.nextCursor().orElse(null),
        page.hasMore());
  }
}
