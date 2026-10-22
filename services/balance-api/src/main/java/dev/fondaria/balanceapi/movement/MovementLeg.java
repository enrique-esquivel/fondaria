package dev.fondaria.balanceapi.movement;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One row as {@code projection.transfer} holds it, already resolved to the leg that matched — the
 * repository's output, before {@link MovementService} turns it into a {@link Movement}.
 *
 * <p>Deliberately still in the projection's own vocabulary: the leg's account id as unsigned
 * decimal, the numeric transfer code, the raw scaled amount. Which fund and which of the
 * participant's two holdings an account id <i>is</i> is knowledge the service holds (it derived the
 * ids in the first place) and the database does not — the CDC stream carries no participant or fund
 * key beyond the account id and the ledger id, by construction (Task 5.5).
 *
 * @param accountId the leg's own account, unsigned decimal u128
 */
public record MovementLeg(
    String transferId,
    String ledgerTimestamp,
    String accountId,
    MovementDirection direction,
    long amountScaled,
    int code,
    LocalDate bookingDate,
    LocalDate valueDate,
    Optional<Long> navVersionId) {

  public MovementLeg {
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(ledgerTimestamp, "ledgerTimestamp must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(bookingDate, "bookingDate must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(navVersionId, "navVersionId must not be null");
  }
}
