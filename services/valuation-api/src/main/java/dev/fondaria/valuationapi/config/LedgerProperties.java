package dev.fondaria.valuationapi.config;

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
 * @param connectTimeout per-endpoint TCP connect timeout, for parity with contribution-api's
 *     equivalent property. This service has no readiness probe of its own yet.
 */
@ConfigurationProperties(prefix = "fondaria.ledger")
public record LedgerProperties(List<String> addresses, Duration connectTimeout) {

  /** The environment variable {@link #addresses} is sourced from, named in startup errors. */
  public static final String ADDRESSES_VARIABLE = "TIGERBEETLE_ADDRESSES";

  public static final String ADDRESSES_REMEDY =
      "Set it to a comma-separated list of TigerBeetle replica addresses, each as host:port "
          + "(for example tigerbeetle:3000). See infrastructure/compose/.env.example. Must resolve "
          + "to the same cluster contribution-api writes to.";

  public LedgerProperties {
    addresses = addresses == null ? List.of() : List.copyOf(addresses);
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
  }
}
