package dev.fondaria.balanceapi.movement;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A position in the movement history: the keyset {@code (ledger_timestamp, transfer_id)} descending
 * (Task 6.3), plus the leg within that transfer.
 *
 * <p><b>Why a third component.</b> The task names a two-part key, and it would be total if a
 * movement were a transfer. It is not: a movement is a <i>leg</i>, and a {@code VESTING} transfer
 * debits the participant's unvested account and credits their vested one, so one {@code
 * projection.transfer} row is two movements sharing a timestamp <i>and</i> an id. Without {@link
 * #leg} the cursor could not say which of the two a page ended on, and the next page would either
 * repeat it or skip it — the exact failure the "every row exactly once" criterion is about. {@code
 * DEBIT} sorts before {@code CREDIT} ({@link MovementDirection#leg()}), so the order is total and
 * stable.
 *
 * <p>Both numbers are unsigned and can exceed a {@code long} ({@code transfer_id} is a u128), so
 * they are {@link BigInteger} here and unsigned decimal text everywhere else — the same convention
 * the CDC decoder and {@code projection.transfer} already use.
 *
 * <p>This is the decoded form. It is never handed to a client: {@link CursorCodec} is what a caller
 * sees, and it is opaque and signed.
 */
public record MovementCursor(BigInteger ledgerTimestamp, BigInteger transferId, int leg) {

  public MovementCursor {
    Objects.requireNonNull(ledgerTimestamp, "ledgerTimestamp must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    if (ledgerTimestamp.signum() < 0 || transferId.signum() < 0) {
      throw new IllegalArgumentException("a ledger id is unsigned");
    }
    if (leg != MovementDirection.DEBIT.leg() && leg != MovementDirection.CREDIT.leg()) {
      throw new IllegalArgumentException("not a leg: " + leg);
    }
  }

  /** The position of a movement, for the page that ends on it. */
  public static MovementCursor of(Movement movement) {
    Objects.requireNonNull(movement, "movement must not be null");
    return new MovementCursor(
        new BigInteger(movement.ledgerTimestamp()),
        new BigInteger(movement.transferId()),
        movement.direction().leg());
  }

  /**
   * Whether {@code leg} sits strictly after this position in the page ordering.
   *
   * <p>Takes the raw projection row rather than a mapped {@link Movement} so that rows about to be
   * discarded — the cursor's own legs, and the row fetched only to answer "is there more" — are
   * never interpreted. Interpreting them would let a data fault on a row that is not on the page
   * fail a request whose page was fine.
   */
  public boolean precedes(MovementLeg leg) {
    Objects.requireNonNull(leg, "leg must not be null");
    int byTimestamp = new BigInteger(leg.ledgerTimestamp()).compareTo(ledgerTimestamp);
    if (byTimestamp != 0) {
      return byTimestamp < 0;
    }
    int byTransfer = new BigInteger(leg.transferId()).compareTo(transferId);
    if (byTransfer != 0) {
      return byTransfer < 0;
    }
    return leg.direction().leg() > this.leg;
  }
}
