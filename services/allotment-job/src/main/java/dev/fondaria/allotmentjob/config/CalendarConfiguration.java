package dev.fondaria.allotmentjob.config;

import dev.fondaria.common.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's one {@link BusinessCalendar} (Task 7.1), used to independently re-derive a staged
 * batch's dealing date via {@code DealingDateRule} — the same computation {@code
 * DealingDateService} performs in contribution-api, over the same batch's stored {@code value_date}
 * and {@code staged_at}.
 *
 * <p>A single bean, for the reason every service in this platform has exactly one: two calendars
 * disagreeing about whether a date is a business date would allot a batch at a valuation date
 * {@code DealingDateService} never promised, or fail to find the NAV valuation-api struck for the
 * date it actually promised.
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
