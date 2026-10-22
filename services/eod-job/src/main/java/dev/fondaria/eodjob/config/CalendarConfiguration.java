package dev.fondaria.eodjob.config;

import dev.fondaria.common.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's one {@link BusinessCalendar} (Task 7.1) — what turns the instant a close pass runs
 * at into the business date it is recorded against.
 *
 * <p>One bean, for the reason every service in this platform has exactly one: two calendars
 * disagreeing about which date an instant belongs to would file a run against a date the rest of
 * the close is not looking at, and the sign-off gate would report no reconciliation for a date that
 * had in fact been reconciled.
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
