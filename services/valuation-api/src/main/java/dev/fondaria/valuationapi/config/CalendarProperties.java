package dev.fondaria.valuationapi.config;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The business calendar valuation dates are checked against (Task 7.1's {@code BusinessCalendar}).
 *
 * <p><b>The holiday list is configuration, and it is a stopgap.</b> Task 7.1 specifies Mexican
 * banking and market holidays as <i>effective-dated reference data</i>, and that table does not
 * exist yet — nothing in the schema holds it, and building one is not in this task's file list. So
 * the dates are bound from the environment here, which is honest about being a placeholder and
 * costs one deletion when the reference table lands.
 *
 * <p>The consequence, recorded rather than hidden: an empty list means weekends are the only
 * non-business dates, so a valuation dated on a Mexican public holiday would be accepted. That is a
 * real gap in {@code valuation-file-v1.md} §4 check 7 until the reference data exists.
 *
 * @param zone the platform's time zone. {@code America/Mexico_City} — Task 7.1.
 * @param cutoffTime a timestamp at or after this belongs to the next business date.
 * @param holidays {@code YYYY-MM-DD} dates that are not business dates.
 */
@ConfigurationProperties(prefix = "fondaria.calendar")
public record CalendarProperties(String zone, String cutoffTime, List<String> holidays) {

  private static final String DEFAULT_ZONE = "America/Mexico_City";
  private static final String DEFAULT_CUTOFF = "18:00";

  public CalendarProperties {
    zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.trim();
    cutoffTime = cutoffTime == null || cutoffTime.isBlank() ? DEFAULT_CUTOFF : cutoffTime.trim();
    holidays = holidays == null ? List.of() : List.copyOf(holidays);
  }

  public ZoneId requireZone() {
    try {
      return ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new ConfigurationException(
          "CALENDAR_ZONE is not a valid IANA time zone id",
          "Set CALENDAR_ZONE to an IANA zone id such as " + DEFAULT_ZONE + ".");
    }
  }

  public LocalTime requireCutoffTime() {
    try {
      return LocalTime.parse(cutoffTime);
    } catch (DateTimeParseException e) {
      throw new ConfigurationException(
          "CALENDAR_CUTOFF_TIME is not a valid HH:MM time",
          "Set CALENDAR_CUTOFF_TIME to a 24-hour time such as " + DEFAULT_CUTOFF + ".");
    }
  }

  /**
   * A malformed holiday fails startup rather than being skipped. Silently dropping one would make
   * that day a business date, and a valuation would be accepted for a date the market was shut —
   * exactly the check this list exists to perform.
   */
  public Set<LocalDate> requireHolidays() {
    Set<LocalDate> parsed = new LinkedHashSet<>();
    for (String holiday : holidays) {
      if (holiday == null || holiday.isBlank()) {
        continue;
      }
      try {
        parsed.add(LocalDate.parse(holiday.trim()));
      } catch (DateTimeParseException e) {
        throw new ConfigurationException(
            "CALENDAR_HOLIDAYS contains a value that is not a YYYY-MM-DD date",
            "Set CALENDAR_HOLIDAYS to a comma-separated list of YYYY-MM-DD dates, or leave it"
                + " unset.");
      }
    }
    return parsed;
  }
}
