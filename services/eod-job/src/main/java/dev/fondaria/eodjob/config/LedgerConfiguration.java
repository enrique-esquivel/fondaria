package dev.fondaria.eodjob.config;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one {@code LedgerPort} implementation (ADR-0008) into this service.
 *
 * <p><b>No {@code TransferIdDeriver}</b>, unlike allotment-job's configuration of the same name —
 * this service never creates a transfer or opens an account. The daily close reads. A
 * reconciliation that could write to the ledger would be able to make itself balance, which is the
 * one thing a control must never be able to do (phase-07: "no code path adjusts participant units
 * to match"). The absence of that bean is what makes it structural rather than a rule someone has
 * to remember, and it is the bean whose absence carries the guarantee: a transfer id is what a
 * write needs.
 *
 * <p>{@code TenantRefDeriver} <b>is</b> wired, added by Task 7.4. It is a read-side derivation and
 * grants nothing: it turns an {@code OrganizationId} into the {@code tenant_id} that {@code
 * projection.participant_units} is partitioned by, which the dense snapshot needs to read a
 * participant's holdings out of one partition instead of scanning every tenant's. Task 7.2 did not
 * need it because the tie-out aggregates per fund and never asks a per-participant question.
 *
 * <p>This is the only place in {@code eod-job} that names a TigerBeetle type; everything downstream
 * depends on {@link LedgerPort} alone (docs/ENGINEERING-RULES.md §4).
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
   * The same namespace as {@link #accountIdDeriver}, and it must be: a tenant ref derived from a
   * different one would not match the {@code user_data_64} already written against the accounts
   * whose ids that deriver produces, and the snapshot would read every participant's partition as
   * empty (ADR-0005).
   */
  @Bean
  public TenantRefDeriver tenantRefDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new TenantRefDeriver(identityProperties.ledgerNamespace());
  }
}
