package dev.fondaria.projection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cluster-wide halt flag's tuning (ADR-0032 §2), bound under {@code fondaria.projection.halt}.
 *
 * @param projectionName which projection's halt row this worker raises and watches. Defaults to
 *     {@code projection-worker}. Deliberately not an environment variable: a deployment has one
 *     projection, and a second name would let two workers each ignore the other's halt. It is a
 *     seam for tests, which share one reused database and must not inherit a halt an earlier run
 *     left behind.
 * @param pollInterval {@code PROJECTION_HALT_POLL_INTERVAL} — how often every replica reads the
 *     flag. This is the bound on how long another replica keeps consuming after a halt is raised.
 *     Defaults to 1 s.
 * @param queryTimeout how long a read or write of the flag may take before it is abandoned (the
 *     next poll retries). Defaults to 5 s. Rounded up to whole seconds: JDBC has no finer grain.
 */
@ConfigurationProperties(prefix = "fondaria.projection.halt")
public record HaltProperties(String projectionName, Duration pollInterval, Duration queryTimeout) {

  public HaltProperties {
    projectionName =
        projectionName == null || projectionName.isBlank()
            ? "projection-worker"
            : projectionName.strip();
    pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
    queryTimeout = queryTimeout == null ? Duration.ofSeconds(5) : queryTimeout;
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new ConfigurationException(
          "PROJECTION_HALT_POLL_INTERVAL must be positive",
          "Set PROJECTION_HALT_POLL_INTERVAL to a positive duration, e.g. 1s.");
    }
    if (queryTimeout.isNegative() || queryTimeout.isZero()) {
      throw new ConfigurationException(
          "the halt flag's query timeout must be positive",
          "Set fondaria.projection.halt.query-timeout to a positive duration, e.g. 5s.");
    }
  }

  /** The JDBC statement timeout, in whole seconds (at least 1). */
  public int queryTimeoutSeconds() {
    return (int) Math.max(1L, (queryTimeout.toMillis() + 999L) / 1000L);
  }
}
