package dev.fondaria.eodjob.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ledger connection settings, bound from the environment.
 *
 * <p>Binding is permissive — an absent {@code TIGERBEETLE_ADDRESSES} binds to an empty list — so
 * that validation can raise a {@link ConfigurationException} naming the variable instead of a
 * binder stack trace.
 *
 * @param addresses TigerBeetle replica addresses as {@code host:port}, in replica-ordinal order
 * @param connectTimeout per-endpoint TCP connect timeout for the readiness probe
 * @param probeCacheTtl how long a probe result is served before the endpoints are re-checked
 */
@ConfigurationProperties(prefix = "fondaria.ledger")
public record LedgerProperties(
    List<String> addresses, Duration connectTimeout, Duration probeCacheTtl) {

  /** The environment variable {@code addresses} is sourced from, named in startup errors. */
  public static final String ADDRESSES_VARIABLE = "TIGERBEETLE_ADDRESSES";

  /**
   * How to fix an absent or malformed {@link #ADDRESSES_VARIABLE}. Held here, not in one of its two
   * consumers, because both the readiness probe and the ledger client reject the same absence and a
   * caller reading two differently-worded remedies for one variable would reasonably wonder which
   * one applied.
   */
  public static final String ADDRESSES_REMEDY =
      "Set it to a comma-separated list of TigerBeetle replica addresses, each as host:port "
          + "(for example tigerbeetle:3000). See infrastructure/compose/.env.example.";

  public LedgerProperties {
    addresses = addresses == null ? List.of() : List.copyOf(addresses);
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
    probeCacheTtl = probeCacheTtl == null ? Duration.ofSeconds(5) : probeCacheTtl;
  }
}
