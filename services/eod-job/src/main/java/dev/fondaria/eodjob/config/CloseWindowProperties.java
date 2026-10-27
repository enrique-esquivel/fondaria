package dev.fondaria.eodjob.config;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When in the business day the scheduled close may start ({@code CloseWindow}).
 *
 * @param start {@code CLOSE_WINDOW_START}, a local {@code HH:MM} in {@code CALENDAR_ZONE}. Blank
 *     means no window: every sweep runs on every tick, which is the production behaviour. The demo
 *     overlays set it so the close runs after the day's payroll and NAV strike, not at 09:00.
 */
@ConfigurationProperties(prefix = "fondaria.close-window")
public record CloseWindowProperties(String start) {

  public static final String START_VARIABLE = "CLOSE_WINDOW_START";

  public CloseWindowProperties {
    start = start == null ? "" : start.trim();
  }

  /**
   * The start, or empty for no window. A start at or after cut-off fails startup: the window would
   * never open, and the close would silently never run.
   */
  public Optional<LocalTime> requireStart(LocalTime cutoffTime) {
    if (start.isEmpty()) {
      return Optional.empty();
    }
    LocalTime parsed;
    try {
      parsed = LocalTime.parse(start);
    } catch (DateTimeParseException e) {
      throw new ConfigurationException(
          START_VARIABLE + " is not a valid HH:MM time",
          "Set " + START_VARIABLE + " to a 24-hour time such as 17:00, or leave it unset.");
    }
    if (!parsed.isBefore(cutoffTime)) {
      throw new ConfigurationException(
          START_VARIABLE + " is not before CALENDAR_CUTOFF_TIME, so the close would never run",
          "Set " + START_VARIABLE + " earlier than CALENDAR_CUTOFF_TIME, or leave it unset.");
    }
    return Optional.of(parsed);
  }
}
