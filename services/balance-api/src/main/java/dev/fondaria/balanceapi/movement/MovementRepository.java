package dev.fondaria.balanceapi.movement;

import java.util.List;
import java.util.Optional;

/**
 * Reads pages of {@code projection.transfer} for one participant's holding accounts (Task 6.3).
 *
 * <p><b>This is the one query endpoint that is allowed to read PostgreSQL on the hot path</b>, and
 * it is not a contradiction of Task 6.2's rule. That rule is about a <i>balance</i>: a balance is
 * authoritative, and the authority for it is the ledger (ADR-0008), so it is never summed from
 * rows. A history is the projection's own reason to exist — the ledger cannot answer "what happened
 * between these two dates" at all — and nothing here adds a number up.
 */
public interface MovementRepository {

  /**
   * One page's worth of legs, newest first, ordered {@code (ledger_timestamp, transfer_id)}
   * descending then leg ascending.
   *
   * @param tenantId the partition key — the organization's {@code TenantRef} as unsigned decimal
   * @param accountIds the participant's holding accounts, unsigned decimal u128
   * @param rowLimit an upper bound on rows returned; the caller asks for slightly more than a page
   *     so it can both discard the cursor's own row and tell whether another page exists
   */
  List<MovementLeg> page(
      String tenantId,
      List<String> accountIds,
      MovementQuery query,
      Optional<MovementCursor> cursor,
      int rowLimit);
}
