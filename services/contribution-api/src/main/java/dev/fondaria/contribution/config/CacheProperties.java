package dev.fondaria.contribution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis connection settings for the cash-receipt response cache, bound from the environment.
 *
 * <p><b>Absence is a startup failure, but an outage is not a request failure.</b> The two are
 * different problems: a deployment that forgot to configure Redis should say so loudly and refuse
 * to start (docs/ENGINEERING-RULES.md §4 — production must fail to start when a required value is
 * absent), whereas a Redis that goes down at three in the morning must cost latency and nothing
 * else. Binding is permissive here for the same reason as {@link DatabaseProperties}: an absent
 * variable becomes a {@link ConfigurationException} naming it, rather than a binder stack trace.
 *
 * @param url Redis URL <b>without credentials</b>, e.g. {@code redis://redis:6379}. The password is
 *     supplied separately so a secret never has to be embedded in a URL, where it would reach any
 *     log line that renders the connection target.
 * @param password the Redis password
 * @param commandTimeout explicit per-command timeout (docs/ENGINEERING-RULES.md §4). Short on
 *     purpose: this is a cache, and waiting on it is worse than missing it.
 * @param cashReceiptTtl how long a resolved cash receipt is replayable from cache — 24 hours, per
 *     Task 3.3 and docs/DESIGN-DECISIONS.md
 * @param traceCorrelationTtl how long a {@code transferId -> traceparent} entry survives (Task
 *     11.2) — long enough to outlast normal CDC pipeline latency, short because a miss only
 *     degrades a trace, never correctness. A few minutes, not hours: unlike the cash-receipt cache
 *     this entry has no reason to outlive the request it was written for.
 */
@ConfigurationProperties(prefix = "fondaria.cache")
public record CacheProperties(
    String url,
    String password,
    Duration commandTimeout,
    Duration cashReceiptTtl,
    Duration traceCorrelationTtl) {

  public static final String URL_VARIABLE = "REDIS_URL";
  public static final String PASSWORD_VARIABLE = "REDIS_PASSWORD";

  static final String REMEDY =
      "Set the Redis connection variables in the environment: "
          + URL_VARIABLE
          + " (a redis:// URL with no credentials in it) and "
          + PASSWORD_VARIABLE
          + ". See infrastructure/compose/.env.example.";

  public CacheProperties {
    commandTimeout = commandTimeout == null ? Duration.ofSeconds(1) : commandTimeout;
    cashReceiptTtl = cashReceiptTtl == null ? Duration.ofHours(24) : cashReceiptTtl;
    traceCorrelationTtl = traceCorrelationTtl == null ? Duration.ofMinutes(5) : traceCorrelationTtl;
  }

  /** Fails startup naming the absent variable, and never echoes a value — a URL is a hostname. */
  public void requireComplete() {
    requirePresent(url, URL_VARIABLE);
    requirePresent(password, PASSWORD_VARIABLE);
  }

  private static void requirePresent(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}
