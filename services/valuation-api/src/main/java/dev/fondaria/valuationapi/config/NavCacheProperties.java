package dev.fondaria.valuationapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the in-memory NAV cache and its Redis warm start (Task 20.6).
 *
 * <p><b>Redis is optional here, and that is the difference from {@code CacheProperties} in
 * contribution-api.</b> There, Redis backs cash-receipt idempotency replay and a deployment that
 * forgot to configure it should refuse to start. Here it is a warm-start convenience the acceptance
 * criteria require the service to survive without ("Redis unavailable does not stop NAV reads"), so
 * an absent URL disables the snapshot store rather than failing startup. Absence is a choice; an
 * outage is already handled.
 *
 * @param url Redis URL <b>without credentials</b>, e.g. {@code redis://redis:6379}. Absent or blank
 *     disables the warm start entirely.
 * @param password the Redis password. Required when {@code url} is set — supplied separately so a
 *     secret never has to be embedded in a URL, where it would reach any log line rendering the
 *     connection target.
 * @param commandTimeout explicit per-command timeout (AGENTS.md §4). Short: this is a cache, and
 *     waiting on it is worse than missing it.
 * @param refreshInterval how long an instance may go before it picks up a price struck elsewhere —
 *     the bound the acceptance criterion means by "the documented interval".
 * @param staleAfter how old a published price may be before it is served marked stale. Above 24
 *     hours because O-21 makes NAV daily: yesterday's price on a normal morning is expected, and a
 *     provider that has gone quiet for over a day is not.
 */
@ConfigurationProperties(prefix = "fondaria.nav-cache")
public record NavCacheProperties(
    String url,
    String password,
    Duration commandTimeout,
    Duration refreshInterval,
    Duration staleAfter) {

  public static final String URL_VARIABLE = "NAV_CACHE_REDIS_URL";
  public static final String PASSWORD_VARIABLE = "NAV_CACHE_REDIS_PASSWORD";

  static final String REMEDY =
      "Either set "
          + PASSWORD_VARIABLE
          + " alongside "
          + URL_VARIABLE
          + ", or unset "
          + URL_VARIABLE
          + " to run the NAV cache without its Redis warm start. "
          + "See infrastructure/compose/.env.example.";

  public NavCacheProperties {
    commandTimeout = commandTimeout == null ? Duration.ofSeconds(1) : commandTimeout;
    refreshInterval = refreshInterval == null ? Duration.ofSeconds(10) : refreshInterval;
    staleAfter = staleAfter == null ? Duration.ofHours(26) : staleAfter;
  }

  /** Whether a Redis warm start is configured at all. */
  public boolean warmStartEnabled() {
    return url != null && !url.isBlank();
  }

  /**
   * A URL without a password is a misconfiguration rather than a choice — it would fail on every
   * command and be swallowed as an outage, which is exactly the sort of silent degradation that
   * should be loud at startup instead. Never echoes a value.
   */
  public void requireCoherent() {
    if (warmStartEnabled() && (password == null || password.isBlank())) {
      throw new ConfigurationException(
          "Environment variable " + URL_VARIABLE + " is set but " + PASSWORD_VARIABLE + " is not",
          REMEDY);
    }
  }
}
