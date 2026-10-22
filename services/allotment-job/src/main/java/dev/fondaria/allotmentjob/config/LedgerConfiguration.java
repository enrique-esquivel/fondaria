package dev.fondaria.allotmentjob.config;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one {@code LedgerPort} implementation (ADR-0008) into this service.
 *
 * <p>Unlike valuation-api's read-only wiring, this service both derives account ids <b>and</b>
 * creates transfers — the {@code CONTRIBUTION_ALLOTMENT} linked chain is this task's entire purpose
 * — so it holds a {@link TransferIdDeriver}, matching contribution-api's bean-for-bean.
 *
 * <p>This is the only place in {@code allotment-job} that names a TigerBeetle type; everything
 * downstream depends on {@link LedgerPort} alone (docs/ENGINEERING-RULES.md §4).
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
   * documented as the namespace for account <i>and</i> transfer ids. Getting it wrong re-derives
   * every transfer id, silently breaking idempotency for every rerun of an interrupted allotment.
   */
  @Bean
  public TransferIdDeriver transferIdDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new TransferIdDeriver(identityProperties.ledgerNamespace());
  }

  /**
   * The tenant ref an organization's movements and per-holder accounts carry in {@code
   * user_data_64} (ADR-0005). Same namespace as the id derivers, for the same reason: a ref under a
   * different namespace would not correspond to that organization's account and transfer ids.
   */
  @Bean
  public TenantRefDeriver tenantRefDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new TenantRefDeriver(identityProperties.ledgerNamespace());
  }
}
