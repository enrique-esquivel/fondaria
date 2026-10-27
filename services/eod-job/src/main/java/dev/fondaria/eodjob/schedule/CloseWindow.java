package dev.fondaria.eodjob.schedule;

import dev.fondaria.common.BusinessCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * When in the business day the scheduled close may run — the one decision the four runners ({@code
 * ReconciliationRunner}, {@code CustodianReconciliationRunner}, {@code SnapshotRunner}, {@code
 * SignoffRunner}) share.
 *
 * <h2>Unset, it is always open, and that is production today</h2>
 *
 * <p>With no {@code CLOSE_WINDOW_START} every sweep runs on every tick, exactly as the close did
 * before this class existed. Nothing here changes a production close.
 *
 * <h2>Set, the close waits for the day's business to be done</h2>
 *
 * <p>Each sweep closes <i>the business date the clock falls in now</i>, and the dense snapshot is
 * taken once per date ({@code SnapshotRunner}). A sweep that fires early in the day therefore
 * snapshots and signs off a book the day's batches and NAV strike have not reached yet, and the
 * month-end statement is rendered from that file. On an hourly timer that is a narrow window; on
 * the demo's compressed day — the clock jumps to 09:00, payroll and the NAV strike follow within a
 * minute, and every beat is a few wall-clock minutes — it is the whole day. A start time moves the
 * close to where it belongs: after the day's business and before cut-off.
 *
 * <p>The window is open while the clock's calendar date <b>is</b> the business date (so before
 * cut-off — after it, the instant already belongs to the next date, whose day has not begun) and
 * its local time is at or past the start. A weekend never opens one: Saturday's instants belong to
 * Monday's business date.
 *
 * <p><b>The controls stop once the date is signed off.</b> Inside a window, reconciliation,
 * custodian reconciliation and the snapshot sweep for a date that already has its {@code
 * workflow.close_signoff} row do nothing more for it: the close they exist to prove is done, and
 * re-tying the whole book on a short demo interval until cut-off would only load the ledger. The
 * sign-off sweep itself keeps running, so an unannounced sign-off is still re-published.
 */
public final class CloseWindow {

  private final Optional<LocalTime> start;
  private final ZoneId zone;
  private final BusinessCalendar calendar;
  private final Clock clock;
  private final Predicate<LocalDate> signedOff;

  private CloseWindow(
      Optional<LocalTime> start,
      ZoneId zone,
      BusinessCalendar calendar,
      Clock clock,
      Predicate<LocalDate> signedOff) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.signedOff = Objects.requireNonNull(signedOff, "signedOff must not be null");
  }

  /** No window: every sweep runs on every tick. */
  public static CloseWindow always(ZoneId zone, BusinessCalendar calendar, Clock clock) {
    return new CloseWindow(Optional.empty(), zone, calendar, clock, date -> false);
  }

  /**
   * A window opening at {@code start} local time on each business date.
   *
   * @param signedOff whether a business date already has its {@code workflow.close_signoff} row
   */
  public static CloseWindow openingAt(
      LocalTime start,
      ZoneId zone,
      BusinessCalendar calendar,
      Clock clock,
      Predicate<LocalDate> signedOff) {
    return new CloseWindow(
        Optional.of(Objects.requireNonNull(start, "start must not be null")),
        zone,
        calendar,
        clock,
        signedOff);
  }

  /** Whether reconciliation, custodian reconciliation and the snapshot may run for today now. */
  public boolean admitsControls() {
    if (start.isEmpty()) {
      return true;
    }
    Instant now = clock.instant();
    return isOpenAt(now) && !signedOff.test(calendar.businessDateOf(now));
  }

  /** Whether sign-off of today may be attempted now. */
  public boolean admitsSignoff() {
    return start.isEmpty() || isOpenAt(clock.instant());
  }

  /** The configured start, if any — for the startup log line. */
  public Optional<LocalTime> start() {
    return start;
  }

  private boolean isOpenAt(Instant now) {
    ZonedDateTime local = now.atZone(zone);
    return local.toLocalDate().equals(calendar.businessDateOf(now))
        && !local.toLocalTime().isBefore(start.orElseThrow());
  }
}
