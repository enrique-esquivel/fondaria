package dev.fondaria.balanceapi.config;

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
 * Wires the one {@code LedgerPort} implementation (ADR-0008). {@code BalanceService} depends on
 * {@link LedgerPort} alone (AGENTS.md §4).
 *
 * <p><b>One other class names a TigerBeetle type:</b> {@code BalanceExceptionHandler} imports
 * {@code LedgerUnavailableException} to map it to a {@code 503}. That type lives in {@code
 * libs/ledger-tigerbeetle} but is really the port's failure contract, and balance-api is the first
 * service outside the adapter module to depend on it. Moving it to {@code libs/ledger-client}
 * beside {@code LedgerPort} would decouple every future service's error handling from the one
 * implementation — a follow-up this task deliberately does not take, since it touches merged code
 * in three services that this task does not name (AGENTS.md §10).
 *
 * <p><b>Read-only by construction of what this service does</b>, not by a wrapper that forbids
 * writes: nothing here calls {@code createTransfer} or {@code createAccount}. In particular this
 * service does <i>not</i> provision a participant's holding accounts — an account that does not
 * exist means the participant has never been allotted units in that fund, which is a correct answer
 * to report, not a gap to fill in on a read.
 *
 * <p>Wrapped in {@link ClusterAwareLedgerPort} for the same reason every other service's is: v1
 * runs a single cluster and a second one must be configuration, never a change to every call site
 * (ADR-0005).
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
