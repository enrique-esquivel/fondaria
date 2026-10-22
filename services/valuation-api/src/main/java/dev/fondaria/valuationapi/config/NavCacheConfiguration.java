package dev.fondaria.valuationapi.config;

import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import dev.fondaria.valuationapi.cache.RedisMetrics;
import dev.fondaria.valuationapi.cache.RedisNavSnapshotStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the NAV cache and its optional Redis warm start (Task 20.6).
 *
 * <p>Lettuce directly, not {@code spring-boot-starter-data-redis}, for the same reason
 * contribution-api's {@code CacheConfiguration} gives: this service issues a handful of commands
 * and one script, and a template plus a serializer stack on top of that would be scaffolding.
 *
 * <p><b>One branching bean rather than {@code @ConditionalOnProperty}.</b> The condition looks
 * right and is not: {@code url: ${NAV_CACHE_REDIS_URL:}} leaves the property <i>present but
 * empty</i> when the variable is unset, and {@code @ConditionalOnProperty} without a {@code
 * havingValue} matches any value that is not {@code "false"} — so the Redis beans would be created
 * for every deployment, configured or not. A plain {@code if} on {@link
 * NavCacheProperties#warmStartEnabled()} says what is meant.
 *
 * <p>The connection lifecycle belongs to {@link RedisNavSnapshotStore} rather than to a
 * {@code @Lazy} bean here, so "a Redis that is down at start-up does not stop this service from
 * starting" is a property of that class and testable without a container.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NavCacheProperties.class)
@EnableScheduling
public class NavCacheConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(NavCacheConfiguration.class);

  /**
   * {@link NavSnapshotStore#NONE} when no URL is configured. Redis is a warm-start convenience the
   * acceptance criteria require the service to survive without ("Redis unavailable does not stop
   * NAV reads"), so its absence is a supported configuration rather than a startup failure — unlike
   * contribution-api's cash-receipt cache, where Redis backs idempotency replay and a missing URL
   * must refuse to start.
   */
  @Bean(destroyMethod = "close")
  public NavSnapshotStore navSnapshotStore(NavCacheProperties properties, RedisMetrics metrics) {
    properties.requireCoherent();
    if (!properties.warmStartEnabled()) {
      LOGGER.info(
          "No {} configured; the NAV cache runs without a Redis warm start and reads PostgreSQL",
          NavCacheProperties.URL_VARIABLE);
      return NavSnapshotStore.NONE;
    }
    return new RedisNavSnapshotStore(redisClient(properties), metrics);
  }

  /**
   * <b>{@code REJECT_COMMANDS} while disconnected is the important line.</b> Lettuce's default is
   * to queue commands until the connection returns or the timeout expires, which turns a stopped
   * Redis into a delay on every sweep. Rejecting immediately makes an outage a fast miss.
   *
   * <p>Not a bean: the store owns it and shuts it down in its own {@code close()}, so there is no
   * second lifecycle for Spring to manage or for a caller to get hold of.
   */
  private static RedisClient redisClient(NavCacheProperties properties) {
    // Built from the URL plus a separately-supplied password rather than from a URL with
    // credentials in it: Lettuce's credentials-provider API hands back a Reactor Mono, which
    // is banned without an ADR (AGENTS.md §4), so the builder is used.
    RedisURI uri =
        RedisURI.builder(RedisURI.create(properties.url()))
            .withPassword(properties.password().toCharArray())
            .withTimeout(properties.commandTimeout())
            .build();
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    client.setDefaultTimeout(properties.commandTimeout());
    return client;
  }

  /**
   * Warm-started here rather than in a {@code @PostConstruct} on the class: {@code libs/valuation}
   * holds no opinion about lifecycle, and this is the one place that knows whether a snapshot store
   * is real.
   *
   * <p>Declared as the concrete type, and only once. {@link InMemoryNavCache} already <i>is</i> a
   * {@code NavCache}, so a second bean exposing the interface would give Spring two candidates for
   * every {@code NavCache} injection point — which is what readers such as {@code NavStrikeService}
   * ask for — and none for the concrete type the sweep needs. One bean satisfies both.
   */
  @Bean
  public InMemoryNavCache navCache(
      FundNavRepository repository,
      NavSnapshotStore snapshots,
      Clock clock,
      NavCacheProperties properties) {
    InMemoryNavCache cache =
        new InMemoryNavCache(repository, snapshots, clock, properties.staleAfter());
    int warmed = cache.warmStart();
    if (warmed > 0) {
      LOGGER.info("NAV cache warm-started with {} funds from the snapshot store", warmed);
    }
    return cache;
  }
}
