package dev.fondaria.contribution.config;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one {@code LedgerPort} implementation (ADR-0008) into the service.
 *
 * <p>This is the only place in {@code contribution-api} that names a TigerBeetle type. Everything
 * downstream — services, repositories, controllers — depends on {@link LedgerPort} alone, so the
 * vendor client cannot leak into application code (AGENTS.md §4).
 *
 * <p>The adapter is wrapped in {@link ClusterAwareLedgerPort} even though v1 runs a single cluster.
 * For one cluster the wrapper is exactly one delegate call, and having it here from the start means
 * a second cluster is an entry in configuration rather than a change to every call site (ADR-0005).
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

  /**
   * The same namespace as {@link #accountIdDeriver}, deliberately — {@code LEDGER_ID_NAMESPACE} is
   * documented as the namespace for account <i>and</i> transfer ids (see {@link
   * IdentityProperties}). A second variable would be one more value a deployment could get wrong,
   * and getting it wrong re-derives every transfer id, silently breaking idempotency for every
   * retry in flight.
   */
  @Bean
  public TransferIdDeriver transferIdDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new TransferIdDeriver(identityProperties.ledgerNamespace());
  }
}
