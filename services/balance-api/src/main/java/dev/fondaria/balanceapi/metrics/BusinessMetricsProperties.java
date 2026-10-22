package dev.fondaria.balanceapi.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for background business-metrics telemetry snapshot sweep (Task 30.10).
 *
 * <p>Metrics emitted are lagging observational snapshots (AGENTS.md §2, ADR-0008). TigerBeetle and
 * reference.fund_nav remain the sole authoritative stores.
 */
@ConfigurationProperties(prefix = "fondaria.business-metrics")
public record BusinessMetricsProperties(Boolean enabled, Duration refreshInterval) {

  public BusinessMetricsProperties {
    enabled = enabled == null ? Boolean.TRUE : enabled;
    refreshInterval = refreshInterval == null ? Duration.ofSeconds(15) : refreshInterval;
  }
}
