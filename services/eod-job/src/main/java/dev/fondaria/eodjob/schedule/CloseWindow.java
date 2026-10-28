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
import java.util.function.Supplier;

/**
 * Which business date the scheduled close works on, and when in the day it may run — the decisions
 * the four runners ({@code ReconciliationRunner}, {@code CustodianReconciliationRunner}, {@code
 * SnapshotRunner}, {@code SignoffRunner}) share.
 *
 * <h2>The close works in date order: the earliest unsigned date first</h2>
 *
 * <p>The date being closed is the business date after the latest {@code workflow.close_signoff}
 * row, or the business date the clock falls in, whichever is earlier ({@link #closingDate()}). On a
 * healthy platform that is always today. It is a <i>past</i> date only when a close did not sign
 * off before its cut-off: the 2026-10-27 load run's 2026-11-03 had breaks at 17:58 only because the
 * projection was still draining, tied at 18:03, and — when every runner closed "the date the clock
 * falls in" — was never signed off by anything. Now the close keeps working on that date until it
 * signs off, and does not start the next one before it does.
 *
 * <p>Two things follow, both deliberate:
 *
 * <ul>
 *   <li><b>The manifest chain stays linear.</b> Date N+1's snapshot cannot be taken while N is
 *       unsigned, so it always links to N's manifest — never past a hole that a later catch-up of N
 *       would then fork.
 *   <li><b>A close that cannot tie halts the close.</b> Reconciliation and the snapshot's tie read
 *       the ledger <i>now</i> ({@code LedgerPort} has no as-of read), so a past date can only tie
 *       until the next date's first unit movement posts. After that, the date stays unsigned, every
 *       later date waits behind it, {@code EODMissedSignoff} pages and {@code
 *       eod_close_dates_behind} counts the dates waiting. A halt is the honest outcome; skipping a
 *       date is a gap in what the platform can prove (docs/ENGINEERING-RULES.md §4 "Data flow").
 * </ul>
 *
 * <p><b>A date behind the latest sign-off is never revisited.</b> Re-opening a past sign-off is a
 * deliberate {@code DELETE} (RUNBOOKS "missed sign-off"), and it must stay deleted; only the dates
 * after the latest sign-off are the close's to work on. With no sign-off ever recorded there is no
 * earlier date to return to, and the close works on today.
 *
 * <h2>Unset, the window is always open, and that is production today</h2>
 *
 * <p>With no {@code CLOSE_WINDOW_START} every sweep runs on every tick for the closing date.
 *
 * <h2>Set, the close waits for the day's business to be done</h2>
 *
 * <p>Each sweep closing <i>today</i> would otherwise snapshot and sign off a book the day's batches
 * and NAV strike have not reached yet, and the dense snapshot is taken once per date ({@code
 * SnapshotRunner}). On the demo's compressed day — the clock jumps to 09:00, payroll and the NAV
 * strike follow within a minute — that is the whole day. A start time moves today's close to where
 * it belongs: after the day's business and before cut-off.
 *
 * <p>For today the window is open while the clock's calendar date <b>is</b> the business date (so
 * before cut-off) and its local time is at or past the start; a weekend never opens one. For a past
 * closing date the window is always open: its business is over, and the reason it is still being
 * closed is that it ran out of window.
 *
 * <p><b>The controls stop once today is signed off.</b> Inside a window, reconciliation, custodian
 * reconciliation and the snapshot do nothing more for a date that already has its sign-off: the
 * close they exist to prove is done. The sign-off sweep itself keeps running, so an unannounced
 * sign-off is still re-published.
 */
public final class CloseWindow {

  private final Optional<LocalTime> start;
  private final ZoneId zone;
  private final BusinessCalendar calendar;
  private final Clock clock;
  private final Supplier<Optional<LocalDate>> latestSignedOff;

  private CloseWindow(
      Optional<LocalTime> start,
      ZoneId zone,
      BusinessCalendar calendar,
      Clock clock,
      Supplier<Optional<LocalDate>> latestSignedOff) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.latestSignedOff =
        Objects.requireNonNull(latestSignedOff, "latestSignedOff must not be null");
  }

  /**
   * No window: every sweep runs on every tick.
   *
   * @param latestSignedOff the latest business date with a {@code workflow.close_signoff} row
   */
  public static CloseWindow always(
      ZoneId zone,
      BusinessCalendar calendar,
      Clock clock,
      Supplier<Optional<LocalDate>> latestSignedOff) {
    return new CloseWindow(Optional.empty(), zone, calendar, clock, latestSignedOff);
  }

  /**
   * A window opening at {@code start} local time on each business date.
   *
   * @param latestSignedOff the latest business date with a {@code workflow.close_signoff} row
   */
  public static CloseWindow openingAt(
      LocalTime start,
      ZoneId zone,
      BusinessCalendar calendar,
      Clock clock,
      Supplier<Optional<LocalDate>> latestSignedOff) {
    return new CloseWindow(
        Optional.of(Objects.requireNonNull(start, "start must not be null")),
        zone,
        calendar,
        clock,
        latestSignedOff);
  }

  /**
   * The business date the close is working on: the one after the latest sign-off, or today if that
   * is earlier or nothing has been signed off.
   */
  public LocalDate closingDate() {
    return closingDate(calendar.businessDateOf(clock.instant()), latestSignedOff.get());
  }

  /** The date reconciliation, custodian reconciliation and the snapshot may run for now, if any. */
  public Optional<LocalDate> dateForControls() {
    Instant now = clock.instant();
    LocalDate today = calendar.businessDateOf(now);
    Optional<LocalDate> latest = latestSignedOff.get();
    LocalDate closing = closingDate(today, latest);
    if (start.isEmpty() || closing.isBefore(today)) {
      return Optional.of(closing);
    }
    boolean todaySignedOff = latest.isPresent() && !latest.get().isBefore(today);
    return isOpenAt(now) && !todaySignedOff ? Optional.of(today) : Optional.empty();
  }

  /** The date sign-off may be attempted for now, if any. */
  public Optional<LocalDate> dateForSignoff() {
    Instant now = clock.instant();
    LocalDate today = calendar.businessDateOf(now);
    LocalDate closing = closingDate(today, latestSignedOff.get());
    if (start.isEmpty() || closing.isBefore(today) || isOpenAt(now)) {
      return Optional.of(closing);
    }
    return Optional.empty();
  }

  /**
   * How many business dates the close is behind: {@code 0} while it is working on today, {@code 1}
   * while yesterday is still unsigned, and so on.
   */
  public long businessDatesBehind() {
    LocalDate today = calendar.businessDateOf(clock.instant());
    long behind = 0;
    for (LocalDate date = closingDate(today, latestSignedOff.get());
        date.isBefore(today);
        date = calendar.nextBusinessDate(date)) {
      behind++;
    }
    return behind;
  }

  /** The configured start, if any — for the startup log line. */
  public Optional<LocalTime> start() {
    return start;
  }

  private LocalDate closingDate(LocalDate today, Optional<LocalDate> latest) {
    if (latest.isEmpty()) {
      return today;
    }
    LocalDate next = calendar.nextBusinessDate(latest.get());
    return next.isBefore(today) ? next : today;
  }

  private boolean isOpenAt(Instant now) {
    ZonedDateTime local = now.atZone(zone);
    return local.toLocalDate().equals(calendar.businessDateOf(now))
        && !local.toLocalTime().isBefore(start.orElseThrow());
  }
}
