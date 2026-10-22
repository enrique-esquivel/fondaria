package dev.fondaria.eodjob.config;

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
 * The business calendar the daily close runs against (Task 7.1's {@code BusinessCalendar}).
 *
 * <p><b>Deliberately a duplicate of the record of the same name in contribution-api, valuation-api,
 * allotment-job and projection-worker</b>, for the reason each of those states: each service binds
 * its own environment, and a shared configuration record would put a Spring Boot binding concern
 * into {@code libs/}. What must not drift is the <i>values</i> — all five read the same {@code
 * CALENDAR_*} variables, set once in {@code infrastructure/compose/.env.example}.
 *
 * <p>What the close uses it for: the business date a reconciliation run is recorded against, taken
 * from an {@code Instant} rather than {@code LocalDate.now()}, so the demo's simulated clock drives
 * it by which instant it supplies.
 *
 * <p>The holiday list is configuration, and it is a stopgap — see contribution-api's record of the
 * same name for the full explanation. An empty list means weekends are the only non-business dates.
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
   * that day a business date, and a batch would deal on a date the market was shut.
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
