package dev.fondaria.balanceapi.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform's time zone (Task 7.1's {@code CALENDAR_ZONE}, read here for the first time in this
 * service).
 *
 * <p><b>Only the zone.</b> contribution-api and allotment-job bind the same prefix to a record that
 * also carries a cut-off time and a holiday list, because they construct a {@code
 * BusinessCalendar}. This service constructs none: nothing it serves is a business date. The one
 * thing it needs a zone for is deciding which calendar month "this period" is, and a record that
 * declared a cut-off it never consulted would invite someone to believe this endpoint honours one.
 *
 * <p><b>A default, unlike the ledger namespace.</b> Getting the zone wrong shifts a period boundary
 * by hours and is visible in {@code periodStart}/{@code periodEnd} on the response itself; getting
 * the namespace wrong reports every participant as holding nothing and looks like a working system.
 * The two failures deserve different treatment, and the default here is the same one the two
 * services that already read this variable use.
 *
 * @param zone an IANA zone id. Defaults to {@code America/Mexico_City}.
 */
@ConfigurationProperties(prefix = "fondaria.calendar")
public record CalendarProperties(String zone) {

  public static final String ZONE_VARIABLE = "CALENDAR_ZONE";

  private static final String DEFAULT_ZONE = "America/Mexico_City";

  public CalendarProperties {
    zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.trim();
  }

  /** Fails startup naming the variable, rather than falling back to a zone nobody chose. */
  public ZoneId requireZone() {
    try {
      return ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new ConfigurationException(
          ZONE_VARIABLE + " is not a valid IANA time zone id",
          "Set " + ZONE_VARIABLE + " to an IANA zone id such as " + DEFAULT_ZONE + ".");
    }
  }
}
