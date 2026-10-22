package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.metrics.BusinessMetricsProperties;
import dev.fondaria.balanceapi.metrics.BusinessMetricsPublisher;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.valuation.NavCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration wiring the business metrics publisher (Task 30.10).
 *
 * <p>Constructor injection only (AGENTS.md §4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessMetricsProperties.class)
@EnableScheduling
public class BusinessMetricsConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "fondaria.business-metrics",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public BusinessMetricsPublisher businessMetricsPublisher(
      MeterRegistry meterRegistry,
      FundDirectory fundDirectory,
      NavCache navCache,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      JdbcClient jdbc,
      Clock clock) {
    return new BusinessMetricsPublisher(
        meterRegistry, fundDirectory, navCache, ledger, accountIds, jdbc, clock);
  }
}
