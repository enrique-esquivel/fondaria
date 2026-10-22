package dev.fondaria.valuationapi.config;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one {@code LedgerPort} implementation (ADR-0008) into this service — the piece Task
 * 20.3 deliberately left out, because units in issue are read at strike time, not at ingestion time
 * (see {@code ValuationApiApplication}'s superseded javadoc).
 *
 * <p>This is the only place in {@code valuation-api} that names a TigerBeetle type; everything
 * downstream — {@code NavStrikeService} — depends on {@link LedgerPort} alone (AGENTS.md §4).
 * Wrapped in {@link ClusterAwareLedgerPort} for the same reason contribution-api's is: v1 runs a
 * single cluster, but a second one then becomes configuration rather than a change to every call
 * site (ADR-0005). This service never calls {@code createTransfer} — the strike only reads.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LedgerClientProperties.class, IdentityProperties.class})
public class LedgerConfiguration {

  /**
   * Closed with the context: the TigerBeetle client owns a native handle and a background thread,
   * and leaking one per restart in a test JVM exhausts both.
   */
  @Bean(destroyMethod = "close")
  public Client tigerBeetleClient(
      LedgerProperties ledgerProperties, LedgerClientProperties clientProperties) {
    if (ledgerProperties.addresses().isEmpty()
        || ledgerProperties.addresses().stream().allMatch(String::isBlank)) {
      throw new ConfigurationException(
          "Required environment variable " + LedgerProperties.ADDRESSES_VARIABLE + " is not set",
          LedgerProperties.ADDRESSES_REMEDY);
    }
    String[] addresses =
        ledgerProperties.addresses().stream().map(String::trim).toArray(String[]::new);
    return new Client(UInt128.asBytes(clientProperties.requireClusterId()), addresses);
  }

  @Bean
  public LedgerPort ledgerPort(
      Client client,
      LedgerClientProperties clientProperties,
      ObservationRegistry observationRegistry) {
    TigerBeetleLedgerAdapter adapter =
        new TigerBeetleLedgerAdapter(client, clientProperties.callTimeout(), observationRegistry);
    return ClusterAwareLedgerPort.singleCluster(
        ClusterId.of(clientProperties.clusterName()), adapter);
  }

  @Bean
  public AccountIdDeriver accountIdDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new AccountIdDeriver(identityProperties.ledgerNamespace());
  }
}
