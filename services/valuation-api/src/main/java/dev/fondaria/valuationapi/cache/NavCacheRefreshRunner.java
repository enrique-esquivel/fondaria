package dev.fondaria.valuationapi.cache;

import dev.fondaria.valuation.InMemoryNavCache;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link InMemoryNavCache#refreshAll()} on the configured interval (Task 20.6).
 *
 * <p><b>This is what makes "within the documented interval" true.</b> An instance that struck a
 * price refreshes that fund immediately; every <i>other</i> instance learns about it here. The
 * interval is therefore the convergence bound the acceptance criterion names, and it is configured
 * rather than constant so a deployment can state its own.
 *
 * <p>Scheduled and also callable directly, the same shape as {@code ValuationIngestionRunner#sweep}
 * — a test drives it explicitly rather than sleeping until a timer happens to fire.
 */
@Component
public class NavCacheRefreshRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(NavCacheRefreshRunner.class);

  private final InMemoryNavCache cache;

  public NavCacheRefreshRunner(InMemoryNavCache cache) {
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
  }

  /**
   * One convergence sweep.
   *
   * @return how many funds are held afterwards
   */
  @Scheduled(
      fixedDelayString = "${fondaria.nav-cache.refresh-interval:10s}",
      initialDelayString = "${fondaria.nav-cache.refresh-interval:10s}")
  public int sweep() {
    int held = cache.refreshAll();
    // The count only. A price is not a balance, but it is not something to scatter through
    // logs either, and the fund ids are already on every strike's log line.
    LOGGER.debug("NAV cache sweep complete; {} funds held", held);
    return held;
  }
}
