package dev.fondaria.statementworker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The completion counter's Redis connection (Task 7.5b, {@code ADR-0022} §3), bound from the
 * environment.
 *
 * <p>Required, unlike {@code valuation-api}'s NAV cache warm start: that Redis is an optional
 * convenience the service must run without; this one is the fan-out's only "done when count ==
 * expected" signal, and a deployment that forgot to configure it should fail loudly at start-up
 * rather than silently generate statements nobody can tell are complete. What is <i>not</i>
 * required is Redis being <b>up</b> at any given moment — {@link
 * dev.fondaria.statementworker.render.RedisCompletionCounter} swallows a connection failure on
 * every call, the same posture {@code RedisNavSnapshotStore} takes, because the counter is a
 * progress signal an operator reads, never the record of whether a statement was correctly rendered
 * and written (that is the object itself, in {@code STATEMENT_BUCKET}).
 *
 * @param url {@code redis://host:port}, no credentials embedded (Lettuce's credentials-provider API
 *     returns a Reactor {@code Mono}, banned without an ADR — docs/ENGINEERING-RULES.md §4).
 * @param password supplied separately and never logged.
 * @param commandTimeout explicit per-call timeout (docs/ENGINEERING-RULES.md §4).
 * @param counterTtl how long an idle period's counter keys survive before Redis may reclaim them.
 *     Long enough for an operator to read "August: 249,998 / 250,000" for the whole of the next
 *     close cycle; short enough that twelve months of counters do not accumulate forever. Applied
 *     on every increment, not just the first, so a slow run's keys do not expire mid-flight.
 */
@ConfigurationProperties(prefix = "fondaria.statement-redis")
public record RedisProperties(
    String url, String password, Duration commandTimeout, Duration counterTtl) {

  public static final String URL_VARIABLE = "STATEMENT_REDIS_URL";
  public static final String PASSWORD_VARIABLE = "STATEMENT_REDIS_PASSWORD";

  static final String REMEDY =
      "Set "
          + URL_VARIABLE
          + " and "
          + PASSWORD_VARIABLE
          + " in the environment. See infrastructure/compose/.env.example.";

  public RedisProperties {
    commandTimeout = commandTimeout == null ? Duration.ofSeconds(1) : commandTimeout;
    counterTtl = counterTtl == null ? Duration.ofDays(45) : counterTtl;
  }

  /**
   * Fails startup naming the absent variable. Never echoes a value (docs/ENGINEERING-RULES.md §7).
   */
  public void requireComplete() {
    if (url == null || url.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + URL_VARIABLE + " is not set", REMEDY);
    }
    if (password == null || password.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + PASSWORD_VARIABLE + " is not set", REMEDY);
    }
  }
}
