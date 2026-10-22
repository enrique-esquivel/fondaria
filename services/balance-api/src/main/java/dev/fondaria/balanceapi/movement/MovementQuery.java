package dev.fondaria.balanceapi.movement;

import dev.fondaria.common.FundId;
import dev.fondaria.common.ParticipantId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One request for a page of movement history: who, which filters, how many, and where to continue
 * from (Task 6.3).
 *
 * <p><b>{@link #limit} is capped, not trusted.</b> 200 maximum, 50 by default, per the contract — a
 * page size is a bound on the work one caller can ask this service to do, so an absent or
 * out-of-range value is refused rather than clamped silently.
 *
 * <p><b>Dates bound {@code value_date}, not {@code booking_date}.</b> {@code O-4}: a statement and
 * a balance are on value date, cursors are on booking date. A participant asking "what happened in
 * August" means the economic month, and {@code value_date} is also the partition key, so the same
 * choice is what lets a single-month query prune to a single partition. For v1 the two dates are
 * equal on every projected row — no CDC field carries an explicit earlier economic date, so {@code
 * JdbcProjectionSink} writes {@code value_date = booking_date} (Task 5.4). The distinction is
 * therefore currently without a difference; it is written this way so that it stays correct on the
 * day a batch-originated posting starts carrying its own value date.
 *
 * @param cursor the opaque continuation token from a previous page, still encoded — decoding it is
 *     {@link CursorCodec}'s job and needs the rest of this record to check it against
 */
public record MovementQuery(
    ParticipantId participantId,
    Optional<LocalDate> from,
    Optional<LocalDate> to,
    Optional<FundId> fundId,
    Optional<MovementTransferCode> transferCode,
    int limit,
    Optional<String> cursor) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAXIMUM_LIMIT = 200;

  /** Field separator in {@link #fingerprint()} — ASCII unit separator, never in an id. */
  private static final String FIELD_SEPARATOR = String.valueOf((char) 0x1F);

  public MovementQuery {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(transferCode, "transferCode must not be null");
    Objects.requireNonNull(cursor, "cursor must not be null");
    if (limit < 1 || limit > MAXIMUM_LIMIT) {
      throw new InvalidMovementQueryException("limit must be between 1 and " + MAXIMUM_LIMIT + ".");
    }
    if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
      throw new InvalidMovementQueryException("from must not be after to.");
    }
  }

  /**
   * Everything the cursor is bound to: change any of it and a cursor issued for the earlier query
   * stops verifying.
   *
   * <p>Deliberately excludes {@link #limit} — changing page size mid-pagination is harmless under
   * keyset pagination and refusing it would be a surprise with no benefit. It includes {@link
   * #participantId} so a cursor cannot be carried from one participant's history to another's, and
   * every filter so a cursor cannot be replayed against a wider window than the one it was issued
   * for.
   */
  String fingerprint() {
    // Separated by the ASCII unit separator rather than concatenated: an absent `from` with a
    // present `to`, and the reverse, would otherwise fingerprint identically, and a cursor
    // issued for one would verify against the other.
    return String.join(
        FIELD_SEPARATOR,
        participantId.toString(),
        from.map(LocalDate::toString).orElse(""),
        to.map(LocalDate::toString).orElse(""),
        fundId.map(FundId::toString).orElse(""),
        transferCode.map(Enum::name).orElse(""));
  }

  /** The same query, continuing from {@code nextCursor}. */
  public MovementQuery continuingFrom(String nextCursor) {
    return new MovementQuery(
        participantId, from, to, fundId, transferCode, limit, Optional.of(nextCursor));
  }
}
