package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the service's {@code LedgerPort}, as distinct from {@link LedgerProperties}, which
 * configures the readiness probe.
 *
 * @param clusterId the TigerBeetle cluster id. Required: a wrong value silently addresses a
 *     different cluster's data, which is the one failure mode worse than not starting.
 * @param clusterName the logical cluster name this deployment's ledger ids resolve to (ADR-0005).
 *     v1 runs a single cluster; a second one is configuration, never a code change.
 * @param callTimeout explicit per-call timeout for every ledger call (AGENTS.md §4)
 */
@ConfigurationProperties(prefix = "fondaria.ledger.client")
public record LedgerClientProperties(Long clusterId, String clusterName, Duration callTimeout) {

  public static final String CLUSTER_ID_VARIABLE = "TIGERBEETLE_CLUSTER_ID";

  private static final String REMEDY =
      "Set it to the numeric id of the TigerBeetle cluster this deployment writes to — the same "
          + "value the cluster was formatted with. See infrastructure/compose/.env.example.";

  public LedgerClientProperties {
    clusterName = clusterName == null || clusterName.isBlank() ? "primary" : clusterName;
    callTimeout = callTimeout == null ? Duration.ofSeconds(5) : callTimeout;
  }

  /** Fails startup naming the absent variable rather than defaulting to cluster {@code 0}. */
  public long requireClusterId() {
    if (clusterId == null) {
      throw new ConfigurationException(
          "Required environment variable " + CLUSTER_ID_VARIABLE + " is not set", REMEDY);
    }
    return clusterId;
  }
}
