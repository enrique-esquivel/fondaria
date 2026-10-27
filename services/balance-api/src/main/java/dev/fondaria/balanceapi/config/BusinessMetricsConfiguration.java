package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.metrics.BusinessMetricsProperties;
import dev.fondaria.balanceapi.metrics.BusinessMetricsPublisher;
import dev.fondaria.common.StorableClock;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.valuation.NavCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration wiring the business metrics publisher (Task 30.10).
 *
 * <p>Constructor injection only (docs/ENGINEERING-RULES.md §4).
 *
 * <p><b>The publisher gets the wall clock, never the service's {@code clock} bean.</b> Its one use
 * of time is {@code fondaria_business_metrics_snapshot_timestamp_seconds}, which Grafana subtracts
 * from {@code time()} — wall time — to show how stale the figures are. Under the demo's {@code
 * SimulatedClock} the injected clock runs hours or days ahead, and the dashboard read a fresh sweep
 * as −15 hours old. Staleness is a fact about the machine, not about the business date.
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
      JdbcClient jdbc) {
    return new BusinessMetricsPublisher(
        meterRegistry,
        fundDirectory,
        navCache,
        ledger,
        accountIds,
        jdbc,
        StorableClock.systemUTC());
  }
}
