package dev.fondaria.common;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a wall-clock timestamp onto a business date, given a cut-off time, a time zone, and a set of
 * holiday dates. Every method is pure and total over its arguments: nothing here reads the system
 * clock, so a caller driving a simulated or accelerated clock (a demo orchestrator, a test)
 * controls the business date entirely by which {@link Instant} it passes in.
 *
 * <p>A timestamp at or after cut-off belongs to the next business date; before cut-off it belongs
 * to its own calendar date. Either way, the result is rolled forward past weekends and holidays to
 * the nearest actual business date.
 */
public final class BusinessCalendar {

  private final ZoneId zone;
  private final LocalTime cutoffTime;
  private final Set<LocalDate> holidays;

  public BusinessCalendar(ZoneId zone, LocalTime cutoffTime, Set<LocalDate> holidays) {
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    this.cutoffTime = Objects.requireNonNull(cutoffTime, "cutoffTime must not be null");
    Objects.requireNonNull(holidays, "holidays must not be null");
    this.holidays = Collections.unmodifiableSet(new LinkedHashSet<>(holidays));
  }

  /**
   * The business date {@code timestamp} falls on. Never reads the system clock — the timestamp is
   * the only source of "now".
   */
  public LocalDate businessDateOf(Instant timestamp) {
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    ZonedDateTime zoned = timestamp.atZone(zone);
    LocalDate calendarDate = zoned.toLocalDate();
    LocalDate candidate =
        zoned.toLocalTime().isBefore(cutoffTime) ? calendarDate : calendarDate.plusDays(1);
    return rollForwardToBusinessDate(candidate);
  }

  /** {@code true} if {@code date} is neither a weekend nor a configured holiday. */
  public boolean isBusinessDate(LocalDate date) {
    Objects.requireNonNull(date, "date must not be null");
    return !isWeekend(date) && !holidays.contains(date);
  }

  /**
   * The next business date strictly after {@code date} — the date a batch or valuation that misses
   * this one rolls to, whether because it missed cut-off or because {@code date} itself is a
   * holiday.
   */
  public LocalDate nextBusinessDate(LocalDate date) {
    Objects.requireNonNull(date, "date must not be null");
    return rollForwardToBusinessDate(date.plusDays(1));
  }

  private LocalDate rollForwardToBusinessDate(LocalDate date) {
    LocalDate candidate = date;
    while (!isBusinessDate(candidate)) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }

  private static boolean isWeekend(LocalDate date) {
    DayOfWeek day = date.getDayOfWeek();
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
  }
}
