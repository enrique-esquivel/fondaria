package dev.fondaria.projection.config;

import dev.fondaria.common.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's one {@link BusinessCalendar} (Task 7.1), used by the transfer projection (Task 5.4)
 * to derive {@code booking_date} from a change event's ledger timestamp — "the business date of the
 * ledger timestamp" (V20's own column comment). {@code value_date} defaults to it (O-4).
 *
 * <p>A single bean, for the reason every service in this platform has exactly one: two calendars
 * disagreeing about whether a date is a business date would book a movement on a day the Phase 7
 * close and reconciliation jobs are not looking at.
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
