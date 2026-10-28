package dev.fondaria.eodjob.signoff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@code workflow.close_signoff} ({@code V29}) — where a sign-off is recorded and read back. */
public interface SignoffRepository {

  /**
   * Records a sign-off, if the date does not already have one.
   *
   * <p>{@code ON CONFLICT (business_date) DO NOTHING}: two {@code eod-job} instances may sweep the
   * same date at once, and the second to arrive is a no-op rather than an error. {@code
   * announced_at} is left null — {@link SignoffService} sets it once the event is confirmed.
   *
   * @return {@code true} if this call wrote the row, {@code false} if it was already there
   */
  boolean insertIfAbsent(CloseSignoff signoff);

  /**
   * Marks a sign-off announced, once {@code close.signed-off.v1} is confirmed.
   *
   * <p>Guarded {@code WHERE announced_at IS NULL}: a re-publish that raced another instance's
   * re-publish does not move the timestamp, and a second call is harmless. The stored value is
   * {@code GREATEST(announcedAt, signed_off_at)} so host clock skew between two instances can never
   * write a timestamp before the sign-off it announces.
   */
  void markAnnounced(LocalDate businessDate, Instant announcedAt);

  /**
   * Every sign-off whose event is not yet confirmed, oldest business date first.
   *
   * <p>What {@link SignoffService} re-publishes on each sweep. Not scoped to the current business
   * date: a broker outage that outlasts the cut-off would otherwise strand yesterday's un-announced
   * sign-off, because the sweep would only ever look at today.
   */
  List<CloseSignoff> findUnannounced();

  /** The sign-off for a date, if it has one. */
  Optional<CloseSignoff> find(LocalDate businessDate);

  /**
   * The latest business date with a sign-off, if any — where {@code CloseWindow} starts counting
   * the dates the close still has to work on.
   */
  Optional<LocalDate> latestSignedOffDate();
}
