package dev.fondaria.valuationapi.config;

import dev.fondaria.common.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's one {@link BusinessCalendar} (Task 7.1).
 *
 * <p>A single bean, so the validator and the ageing report can never disagree about whether a date
 * is a business date — one of them refusing a valuation the other then reports as missing would be
 * an unresolvable state for an operator.
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
