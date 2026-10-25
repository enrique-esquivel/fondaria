package dev.fondaria.projection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The projection's own activity log (ADR-0035), bound under {@code fondaria.projection.activity}.
 *
 * @param logInterval {@code PROJECTION_ACTIVITY_LOG_INTERVAL} — how often each replica logs one
 *     INFO summary of what it consumed and what its queues still hold. Defaults to 1 minute.
 * @param depthTimeout how long reading every projection queue's ready depth from the broker may
 *     take before the summary is logged without it. Defaults to 5 s.
 */
@ConfigurationProperties(prefix = "fondaria.projection.activity")
public record ActivityProperties(Duration logInterval, Duration depthTimeout) {

  public ActivityProperties {
    logInterval = logInterval == null ? Duration.ofMinutes(1) : logInterval;
    depthTimeout = depthTimeout == null ? Duration.ofSeconds(5) : depthTimeout;
    if (logInterval.isNegative() || logInterval.isZero()) {
      throw new ConfigurationException(
          "PROJECTION_ACTIVITY_LOG_INTERVAL must be positive",
          "Set PROJECTION_ACTIVITY_LOG_INTERVAL to a positive duration, e.g. 1m.");
    }
    if (depthTimeout.isNegative() || depthTimeout.isZero()) {
      throw new ConfigurationException(
          "the activity log's queue depth timeout must be positive",
          "Set fondaria.projection.activity.depth-timeout to a positive duration, e.g. 5s.");
    }
  }
}
