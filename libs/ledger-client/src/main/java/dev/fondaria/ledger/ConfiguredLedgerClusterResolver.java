package dev.fondaria.ledger;

import java.util.Map;
import java.util.Objects;

/**
 * {@link LedgerClusterResolver} backed by a default cluster plus per-ledger-id overrides, both
 * supplied at construction (ADR-0005). A default is required because fund unit ledger ids are
 * assigned dynamically at fund creation (Task 2.1) and can never be fully enumerated up front; most
 * funds simply take the default, and only a fund given a segregated cluster needs an override
 * entry.
 */
public final class ConfiguredLedgerClusterResolver implements LedgerClusterResolver {

  private final ClusterId defaultCluster;
  private final Map<Long, ClusterId> overridesByLedgerId;

  public ConfiguredLedgerClusterResolver(
      ClusterId defaultCluster, Map<Long, ClusterId> overridesByLedgerId) {
    this.defaultCluster = Objects.requireNonNull(defaultCluster, "defaultCluster must not be null");
    Objects.requireNonNull(overridesByLedgerId, "overridesByLedgerId must not be null");
    this.overridesByLedgerId = Map.copyOf(overridesByLedgerId);
  }

  /** Every ledger id — cash and every fund, present or future — resolves to {@code cluster}. */
  public static ConfiguredLedgerClusterResolver singleCluster(ClusterId cluster) {
    return new ConfiguredLedgerClusterResolver(cluster, Map.of());
  }

  @Override
  public ClusterId resolveCluster(long ledgerId) {
    return overridesByLedgerId.getOrDefault(ledgerId, defaultCluster);
  }
}
