package dev.fondaria.projection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often a replica compares the entries queues it owns with its fair share (ADR-0042), bound
 * under {@code fondaria.projection.rebalance}.
 *
 * @param interval {@code PROJECTION_REBALANCE_INTERVAL} — how often each replica runs a rebalance
 *     pass. It also bounds how long a dead pod's entries queues wait for a survivor to stand by for
 *     them again. Zero turns rebalancing off, which leaves every queue with whichever pod took it
 *     first. Defaults to 30 s.
 */
@ConfigurationProperties(prefix = "fondaria.projection.rebalance")
public record RebalanceProperties(Duration interval) {

  public RebalanceProperties {
    interval = interval == null ? Duration.ofSeconds(30) : interval;
    if (interval.isNegative()) {
      throw new ConfigurationException(
          "PROJECTION_REBALANCE_INTERVAL must not be negative",
          "Set PROJECTION_REBALANCE_INTERVAL to a positive duration, e.g. 30s, or 0 to disable.");
    }
  }

  public boolean enabled() {
    return !interval.isZero();
  }
}
