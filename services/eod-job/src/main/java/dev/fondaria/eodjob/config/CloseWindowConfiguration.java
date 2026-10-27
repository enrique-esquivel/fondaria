package dev.fondaria.eodjob.config;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.eodjob.schedule.CloseWindow;
import dev.fondaria.eodjob.signoff.SignoffGate;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link CloseWindow} the four close runners share. Which window was built is logged once
 * at startup, as the clock is, rather than left to be inferred from a close that did not run.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloseWindowProperties.class)
public class CloseWindowConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(CloseWindowConfiguration.class);

  @Bean
  public CloseWindow closeWindow(
      CloseWindowProperties properties,
      CalendarProperties calendarProperties,
      BusinessCalendar calendar,
      Clock clock,
      SignoffGate signoffGate) {
    ZoneId zone = calendarProperties.requireZone();
    Optional<LocalTime> start = properties.requireStart(calendarProperties.requireCutoffTime());
    if (start.isEmpty()) {
      LOGGER.info("Close window: none (CLOSE_WINDOW_START is unset) -- every sweep runs");
      return CloseWindow.always(zone, calendar, clock);
    }
    LOGGER.info(
        "Close window: opens at {} {} on each business date, until cut-off", start.get(), zone);
    return CloseWindow.openingAt(start.get(), zone, calendar, clock, signoffGate::isSignedOff);
  }
}
