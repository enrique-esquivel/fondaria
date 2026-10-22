package dev.fondaria.balanceapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for this service's in-memory NAV cache (Task 20.6's {@code InMemoryNavCache}, read
 * side).
 *
 * <p><b>No Redis here, unlike valuation-api.</b> There the snapshot store is a warm-start
 * convenience for the instance that also strikes prices; here the cache is purely a reader and
 * converges on {@code reference.fund_nav} within {@link #refreshInterval()} of a strike anywhere. A
 * snapshot store would buy only a faster first sweep on a cold start — and a cold start already
 * requires the database, since Flyway migrates before this service serves anything. So the cache
 * runs with {@code NavSnapshotStore.NONE} and this service carries no Redis dependency at all.
 *
 * @param refreshInterval how long this instance may go before it picks up a price struck elsewhere.
 *     Shorter than valuation-api's would be pointless — that is the instance doing the striking.
 * @param staleAfter how old a published price may be before every figure it values is served marked
 *     stale. Above 24 hours because NAV is daily (O-21): yesterday's price on a normal morning is
 *     expected, a provider quiet for more than a day is not. <b>A stale price is still served</b> —
 *     never withheld, never silently — because a participant seeing an old number labelled old is
 *     better served than one seeing an error.
 */
@ConfigurationProperties(prefix = "fondaria.nav-cache")
public record NavCacheProperties(Duration refreshInterval, Duration staleAfter) {

  public NavCacheProperties {
    refreshInterval = refreshInterval == null ? Duration.ofSeconds(10) : refreshInterval;
    staleAfter = staleAfter == null ? Duration.ofHours(26) : staleAfter;
  }
}
