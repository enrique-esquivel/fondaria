package dev.fondaria.demo;

import dev.fondaria.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;

/**
 * The same cut-off every service in this platform uses (`phase-D-demo.md` § "Time, honestly":
 * "cut-off 18:00 America/Mexico_City"). No holiday calendar -- this module only needs weekday
 * rolling, and a demo run is short enough that a real holiday list would add configuration nobody
 * reads.
 */
public final class DemoCalendar {

  public static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  public static final LocalTime CUTOFF = LocalTime.of(18, 0);

  private DemoCalendar() {}

  public static BusinessCalendar create() {
    return new BusinessCalendar(ZONE, CUTOFF, Set.of());
  }

  /**
   * The month's last business date -- the close whose sign-off fans the month's statements out. The
   * same definition eod-job's {@code SignoffService} uses: the next business date falls in a later
   * month.
   */
  public static LocalDate lastBusinessDayOf(BusinessCalendar calendar, YearMonth month) {
    LocalDate candidate = month.atEndOfMonth();
    while (!calendar.isBusinessDate(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  /** Whether {@code date} is its month's last business date. */
  public static boolean isLastBusinessDayOfMonth(BusinessCalendar calendar, LocalDate date) {
    return !YearMonth.from(calendar.nextBusinessDate(date)).equals(YearMonth.from(date));
  }
}
