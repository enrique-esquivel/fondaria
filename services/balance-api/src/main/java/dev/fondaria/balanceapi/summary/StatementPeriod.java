package dev.fondaria.balanceapi.summary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The window "contributions this period" is measured over: a calendar month, both ends inclusive.
 *
 * <h2>Why a calendar month, and why this task had to choose</h2>
 *
 * <p>{@code phase-06-query-api.md} Task 6.5 asks for "contributions this period" and never says
 * what a period is; Phase 7 owns statement periods and has not been built. Three properties made
 * the calendar month the only candidate that could be defended without inventing configuration a
 * later phase would have to unpick: it is derivable from a date alone, so no plan, product or
 * organization has to carry a period definition for this endpoint to answer; it is what an
 * employer's payroll cycle already is; and it is the granularity {@code projection.transfer} is
 * physically partitioned at (one leaf per month, {@code V20}), so the query prunes to exactly the
 * data it reads.
 *
 * <p><b>When Phase 7 defines a statement period that is not a calendar month, this becomes wrong
 * rather than merely coarse</b> — the two figures would carry the same name and cover different
 * days. That is why {@code periodStart} and {@code periodEnd} are on the response body: a reader
 * can always see which days the number covers, and a mismatch is visible rather than silent.
 *
 * <h2>Why the boundary is taken in the platform's zone, not UTC</h2>
 *
 * <p>The clock this service wires is {@code StorableClock.systemUTC()}. Taking the month from it
 * directly would roll the period over at 18:00 in Mexico City on the last day of the month, so an
 * employer looking at their summary on the evening of 31 August would be shown September's
 * contributions — zero. The zone is {@code CALENDAR_ZONE}, the same variable the two services with
 * a {@code BusinessCalendar} already read.
 *
 * <h2>Value date, not booking date</h2>
 *
 * <p>Backlog row 2 settled it: balances and statements are on the value date, cursors on the
 * booking date (O-4). A contribution belongs to the month it was <i>for</i>, and this figure sits
 * beside a valuation.
 *
 * <p><b>In v1 that distinction does not yet bite, and the docs must not pretend it does.</b> {@code
 * JdbcProjectionSink} writes {@code value_date = booking_date} on every projected row, because the
 * CDC event carries no explicit value date (see {@code BACKLOG.md}). So today the figure is really
 * an <i>allotment</i>-date total: an August payroll batch allotted on 2 September is reported in
 * September. The query reads the right column and becomes correct for free when O-4's explicit
 * value date lands; until then an employer reconciling against their own payroll may see the shift.
 *
 * @param start first day of the period, inclusive
 * @param end last day of the period, inclusive
 */
public record StatementPeriod(LocalDate start, LocalDate end) {

  public StatementPeriod {
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
    if (end.isBefore(start)) {
      throw new IllegalArgumentException("a period must not end before it starts");
    }
  }

  /** The calendar month containing {@code date}. */
  public static StatementPeriod monthOf(LocalDate date) {
    Objects.requireNonNull(date, "date must not be null");
    return new StatementPeriod(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()));
  }

  /** The calendar month {@code at} falls in, read in {@code zone}. */
  public static StatementPeriod monthOf(Instant at, ZoneId zone) {
    Objects.requireNonNull(at, "at must not be null");
    Objects.requireNonNull(zone, "zone must not be null");
    return monthOf(LocalDate.ofInstant(at, zone));
  }
}
