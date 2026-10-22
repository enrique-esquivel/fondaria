package dev.fondaria.contribution.config;

import dev.fondaria.common.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's one {@link BusinessCalendar} (Task 7.1), used by {@code DealingDateService} to
 * answer which valuation date a staged batch will deal at (Task 21.1).
 *
 * <p>A single bean, for the same reason {@code valuation-api} has exactly one: two calendars
 * disagreeing about whether a date is a business date would let this service promise a participant
 * a dealing date the valuation service will never strike a NAV for, and a batch stuck waiting for a
 * price that cannot exist is not a state an operator can resolve.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarProperties.class)
public class CalendarConfiguration {

  @Bean
  public BusinessCalendar businessCalendar(CalendarProperties properties) {
    return new BusinessCalendar(
        properties.requireZone(), properties.requireCutoffTime(), properties.requireHolidays());
  }
}
