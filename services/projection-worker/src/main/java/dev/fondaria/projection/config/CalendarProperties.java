package dev.fondaria.projection.config;

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
 * The business calendar the transfer projection stamps {@code booking_date} / {@code value_date}
 * with (Task 5.4), from Task 7.1's {@code BusinessCalendar}.
 *
 * <p><b>Deliberately a duplicate of contribution-api's, valuation-api's and allotment-job's records
 * of the same name</b> — each service binds its own environment, and a shared configuration record
 * would put a Spring Boot binding concern into {@code libs/}. What must not drift is the
 * <i>values</i>: a movement this projection books on business date D must be the same D the close
 * and reconciliation jobs (Phase 7) will look for. All four services read the same {@code
 * CALENDAR_*} variables, set once in {@code infrastructure/compose/.env.example}.
 *
 * <p>The holiday list is configuration, and it is a stopgap — see contribution-api's record of the
 * same name for the full explanation. An empty list means weekends are the only non-business dates,
 * so a movement committed on a Mexican public holiday books on that day rather than the next
 * business one.
 *
 * @param zone the platform's time zone. {@code America/Mexico_City} — Task 7.1.
 * @param cutoffTime a ledger timestamp at or after this belongs to the next business date.
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
   * that day a business date, and a movement would be booked on a date the market was shut.
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
