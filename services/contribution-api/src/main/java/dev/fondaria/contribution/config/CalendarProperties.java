package dev.fondaria.contribution.config;

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
 * The business calendar contribution batches are dealt against (Task 7.1's {@code
 * BusinessCalendar}, applied by {@code DealingDateRule} for Task 21.1).
 *
 * <p><b>Deliberately a duplicate of {@code valuation-api}'s record of the same name</b>, the way
 * {@code IdentityProperties} is: each service binds its own environment, and a shared configuration
 * record would put a Spring Boot binding concern into {@code libs/}. What must not drift is the
 * <i>values</i> — a batch dealt at a valuation date {@code valuation-api} refuses to strike is a
 * batch that never gets priced — so both services read the same {@code CALENDAR_*} variables, and
 * {@code infrastructure/compose/.env.example} sets them once for both.
 *
 * <p><b>The holiday list is configuration, and it is a stopgap.</b> Task 7.1 specifies Mexican
 * banking and market holidays as <i>effective-dated reference data</i>, and that table does not
 * exist yet — nothing in the schema holds it, and building one is not in this task's file list. So
 * the dates are bound from the environment here, which is honest about being a placeholder and
 * costs one deletion when the reference table lands.
 *
 * <p>The consequence, recorded rather than hidden: an empty list means weekends are the only
 * non-business dates, so a batch whose value date is a Mexican public holiday deals on that day
 * rather than rolling to the next one. That is a real gap until the reference data exists.
 *
 * @param zone the platform's time zone. {@code America/Mexico_City} — Task 7.1.
 * @param cutoffTime a timestamp at or after this belongs to the next business date. For a
 *     contribution batch, this is what makes a batch staged one second either side of it deal on
 *     different valuation dates.
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
