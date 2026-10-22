package dev.fondaria.projection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task 11.2's trace-correlation Redis, bound from the environment under {@code
 * fondaria.trace-redis}.
 *
 * <p><b>Must resolve to the same Redis instance as {@code contribution-api}'s {@code REDIS_URL}.
 * </b> This is the reader half of a producer/consumer pair, not an independent cache: {@code
 * contribution-api}'s {@code TraceCorrelationPublisher} writes a {@code transferId -> traceparent}
 * entry there, and this service reads it back by the same key. A different Redis here is not a
 * fault — every read is simply a permanent miss, degrading to an unlinked span.
 *
 * <p><b>Optional, unlike {@code contribution-api}'s cache.</b> This service has no business
 * requirement on Redis at all (it exists to be killed at any instant and still converge), so an
 * absent URL disables trace correlation entirely rather than failing startup — the same choice
 * {@code valuation-api}'s {@code NavCacheProperties} makes for its own optional warm start.
 *
 * @param url Redis URL <b>without credentials</b>. Absent or blank disables trace correlation.
 * @param password the Redis password. Required when {@code url} is set.
 * @param commandTimeout explicit per-command timeout (AGENTS.md §4). Short: a miss must never cost
 *     more than a cheap timeout on the hot path of every projected transfer.
 */
@ConfigurationProperties(prefix = "fondaria.trace-redis")
public record TraceRedisProperties(String url, String password, Duration commandTimeout) {

  public static final String URL_VARIABLE = "TRACE_CORRELATION_REDIS_URL";
  public static final String PASSWORD_VARIABLE = "TRACE_CORRELATION_REDIS_PASSWORD";

  static final String REMEDY =
      "Either set "
          + PASSWORD_VARIABLE
          + " alongside "
          + URL_VARIABLE
          + ", or unset "
          + URL_VARIABLE
          + " to run without trace correlation. See infrastructure/compose/.env.example.";

  public TraceRedisProperties {
    commandTimeout = commandTimeout == null ? Duration.ofMillis(200) : commandTimeout;
  }

  public boolean enabled() {
    return url != null && !url.isBlank();
  }

  /** A URL without a password would fail on every command and be swallowed as a permanent miss. */
  public void requireCoherent() {
    if (enabled() && (password == null || password.isBlank())) {
      throw new ConfigurationException(
          "Environment variable " + URL_VARIABLE + " is set but " + PASSWORD_VARIABLE + " is not",
          REMEDY);
    }
  }
}
