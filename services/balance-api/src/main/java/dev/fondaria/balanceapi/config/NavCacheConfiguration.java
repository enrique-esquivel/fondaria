package dev.fondaria.balanceapi.config;

import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires the read-only NAV cache and the sweep that keeps it converging on {@code
 * reference.fund_nav}.
 *
 * <p>Declared as the concrete {@link InMemoryNavCache} and only once, for the reason
 * valuation-api's equivalent records: it already <i>is</i> a {@code NavCache}, so a second bean
 * exposing the interface would give Spring two candidates at every injection point.
 *
 * <p>{@link NavSnapshotStore#NONE} — see {@link NavCacheProperties} for why this service carries no
 * Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NavCacheProperties.class)
@EnableScheduling
public class NavCacheConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(NavCacheConfiguration.class);

  private final InMemoryNavCache cache;

  public NavCacheConfiguration(
      FundNavRepository repository, Clock clock, NavCacheProperties properties) {
    this.cache =
        new InMemoryNavCache(repository, NavSnapshotStore.NONE, clock, properties.staleAfter());
  }

  /**
   * Swept once, eagerly, before the first request rather than after the first interval.
   *
   * <p>This matters more here than the equivalent warm start does in valuation-api. {@code
   * NavCache.currentAll()} — how the balance path discovers which funds exist — deliberately does
   * not read through, so an unswept cache would report every participant as holding nothing for the
   * first interval: a wrong balance wearing the shape of a right one. A failure is logged, not
   * fatal; the scheduled sweep retries, and Flyway has already proved the database reachable by the
   * time this runs.
   */
  @Bean
  public InMemoryNavCache navCache() {
    try {
      LOGGER.info("NAV cache primed with {} funds", cache.refreshAll());
    } catch (RuntimeException e) {
      LOGGER.warn("NAV cache could not be primed at startup; the sweep will retry", e);
    }
    return cache;
  }

  /**
   * The convergence sweep. Failure is logged and swallowed deliberately: a database blip must not
   * leave the scheduler's next run cancelled, and the cache keeps serving its last known prices —
   * marked stale once they age — which is precisely the degradation this design is for.
   */
  @Scheduled(
      fixedDelayString = "${fondaria.nav-cache.refresh-interval:10s}",
      initialDelayString = "${fondaria.nav-cache.refresh-interval:10s}")
  public void sweep() {
    try {
      cache.refreshAll();
    } catch (RuntimeException e) {
      LOGGER.warn("NAV cache sweep failed; serving last known prices", e);
    }
  }
}
