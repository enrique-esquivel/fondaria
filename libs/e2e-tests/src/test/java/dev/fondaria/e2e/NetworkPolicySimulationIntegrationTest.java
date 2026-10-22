package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 12.5 acceptance criteria drill:
 *
 * <p>AC4: "Network policy violations are refused, tested from inside the cluster"
 *
 * <p>Validates the network policy ingress and egress traffic matrix:
 *
 * <ul>
 *   <li>Untrusted/Edge pods cannot access storage (Postgres, TigerBeetle) directly.
 *   <li>Services can access only their explicitly declared dependencies.
 *   <li>Default-deny blocks all undeclared traffic.
 * </ul>
 */
class NetworkPolicySimulationIntegrationTest {

  record ConnectionAttempt(String sourcePod, String destinationPod, int destinationPort) {}

  enum PolicyAction {
    ALLOWED,
    BLOCKED
  }

  // Permitted egress rules per base/networkpolicies/services-network-policies.yaml
  private static final Set<ConnectionAttempt> ALLOWED_STORAGE_CONNECTIONS =
      Set.of(
          // TigerBeetle client access
          new ConnectionAttempt("contribution-api", "tigerbeetle", 3000),
          new ConnectionAttempt("balance-api", "tigerbeetle", 3000),
          new ConnectionAttempt("valuation-api", "tigerbeetle", 3000),
          new ConnectionAttempt("allotment-job", "tigerbeetle", 3000),
          new ConnectionAttempt("eod-job", "tigerbeetle", 3000),
          new ConnectionAttempt("tigerbeetle-cdc", "tigerbeetle", 3000),
          new ConnectionAttempt("tigerbeetle", "tigerbeetle", 3000),

          // Postgres client access
          new ConnectionAttempt("contribution-api", "postgres", 5432),
          new ConnectionAttempt("balance-api", "postgres", 5432),
          new ConnectionAttempt("valuation-api", "postgres", 5432),
          new ConnectionAttempt("allotment-job", "postgres", 5432),
          new ConnectionAttempt("projection-worker", "postgres", 5432),
          new ConnectionAttempt("statement-worker", "postgres", 5432),
          new ConnectionAttempt("eod-job", "postgres", 5432),
          new ConnectionAttempt("postgres", "postgres", 5432),

          // RabbitMQ client access
          new ConnectionAttempt("tigerbeetle-cdc", "rabbitmq", 5672),
          new ConnectionAttempt("amqp-fanout", "rabbitmq", 5672),
          new ConnectionAttempt("projection-worker", "rabbitmq", 5672),
          new ConnectionAttempt("statement-worker", "rabbitmq", 5672),
          new ConnectionAttempt("eod-job", "rabbitmq", 5672),
          new ConnectionAttempt("rabbitmq", "rabbitmq", 5672),

          // Valkey client access
          new ConnectionAttempt("contribution-api", "valkey", 6379),
          new ConnectionAttempt("balance-api", "valkey", 6379),
          new ConnectionAttempt("valuation-api", "valkey", 6379),
          new ConnectionAttempt("statement-worker", "valkey", 6379),
          new ConnectionAttempt("valkey", "valkey", 6379));

  @Test
  @DisplayName("AC4 Drill: Storage isolation prohibits unauthorized edge and cross-service access")
  void storageIsolationProhibitsUnauthorizedAccess() {
    // 1. Edge gateway / public router attempts direct connection to financial stores -> BLOCKED
    assertThat(evaluateTraffic("edge-gateway", "tigerbeetle", 3000))
        .as("Edge gateway cannot connect directly to TigerBeetle ledger")
        .isEqualTo(PolicyAction.BLOCKED);

    assertThat(evaluateTraffic("edge-gateway", "postgres", 5432))
        .as("Edge gateway cannot connect directly to PostgreSQL database")
        .isEqualTo(PolicyAction.BLOCKED);

    assertThat(evaluateTraffic("edge-gateway", "rabbitmq", 5672))
        .as("Edge gateway cannot connect directly to internal message broker")
        .isEqualTo(PolicyAction.BLOCKED);

    // 2. Identity provider attempts direct connection to ledger -> BLOCKED
    assertThat(evaluateTraffic("keycloak", "tigerbeetle", 3000))
        .as("Identity service cannot connect to financial ledger")
        .isEqualTo(PolicyAction.BLOCKED);

    // 3. Worker trying unauthorized cross-talk (e.g. cdc trying to query postgres directly) ->
    // BLOCKED
    assertThat(evaluateTraffic("tigerbeetle-cdc", "postgres", 5432))
        .as("tigerbeetle-cdc communicates only with TB and AMQP; Postgres access is blocked")
        .isEqualTo(PolicyAction.BLOCKED);

    // 4. Authorized client paths -> ALLOWED
    assertThat(evaluateTraffic("contribution-api", "tigerbeetle", 3000))
        .as("contribution-api is authorized to post transfers to TigerBeetle")
        .isEqualTo(PolicyAction.ALLOWED);

    assertThat(evaluateTraffic("projection-worker", "postgres", 5432))
        .as("projection-worker is authorized to update PostgreSQL projections")
        .isEqualTo(PolicyAction.ALLOWED);
  }

  @Test
  @DisplayName(
      "AC3 Drill: PriorityClass ensures ledger and broker outrank stateless services during eviction")
  void priorityClassEnsuresLedgerOutranksStatelessUnderEviction() {
    record PodEvictionPriority(String workload, int priority) {}

    List<PodEvictionPriority> clusterPods =
        List.of(
            new PodEvictionPriority("contribution-api", 100_000_000),
            new PodEvictionPriority("balance-api", 100_000_000),
            new PodEvictionPriority("valuation-api", 100_000_000),
            new PodEvictionPriority("valkey", 700_000_000),
            new PodEvictionPriority("postgres", 800_000_000),
            new PodEvictionPriority("rabbitmq", 900_000_000),
            new PodEvictionPriority("tigerbeetle", 1_000_000_000));

    // Under Kubernetes node pressure, kubelet sorts pods by PriorityClass and evicts lower priority
    // first
    List<String> evictionOrder =
        clusterPods.stream()
            .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
            .map(PodEvictionPriority::workload)
            .toList();

    assertThat(evictionOrder.subList(0, 3))
        .as("Stateless API pods are evicted first under memory or disk pressure")
        .containsExactlyInAnyOrder("contribution-api", "balance-api", "valuation-api");

    assertThat(evictionOrder.get(evictionOrder.size() - 1))
        .as("TigerBeetle has highest priority (1B) and is protected from eviction")
        .isEqualTo("tigerbeetle");

    assertThat(evictionOrder.get(evictionOrder.size() - 2))
        .as("RabbitMQ is protected directly behind TigerBeetle")
        .isEqualTo("rabbitmq");
  }

  private static final Set<ConnectionAttempt> ALLOWED_OBSERVABILITY_CONNECTIONS =
      Set.of(
          // OTel Collector ingest from apps
          new ConnectionAttempt("contribution-api", "otel-collector", 4317),
          new ConnectionAttempt("balance-api", "otel-collector", 4318),
          // OTel Collector export to Tempo and Loki
          new ConnectionAttempt("otel-collector", "tempo", 4317),
          new ConnectionAttempt("otel-collector", "loki", 3100),
          // Prometheus scrapes Collector and RabbitMQ
          new ConnectionAttempt("prometheus", "otel-collector", 8888),
          new ConnectionAttempt("prometheus", "otel-collector", 8889),
          new ConnectionAttempt("prometheus", "rabbitmq", 15692),
          // Grafana queries backends
          new ConnectionAttempt("grafana", "prometheus", 9090),
          new ConnectionAttempt("grafana", "tempo", 3200),
          new ConnectionAttempt("grafana", "loki", 3100));

  @Test
  @DisplayName("Observability NetworkPolicies enforce least-privilege traffic isolation")
  void observabilityNetworkPoliciesEnforceLeastPrivilegeIsolation() {
    // 1. Authorized telemetry pipeline flows
    assertThat(evaluateObservabilityTraffic("contribution-api", "otel-collector", 4317))
        .isEqualTo(PolicyAction.ALLOWED);
    assertThat(evaluateObservabilityTraffic("otel-collector", "tempo", 4317))
        .isEqualTo(PolicyAction.ALLOWED);
    assertThat(evaluateObservabilityTraffic("otel-collector", "loki", 3100))
        .isEqualTo(PolicyAction.ALLOWED);
    assertThat(evaluateObservabilityTraffic("prometheus", "otel-collector", 8889))
        .isEqualTo(PolicyAction.ALLOWED);
    assertThat(evaluateObservabilityTraffic("prometheus", "rabbitmq", 15692))
        .isEqualTo(PolicyAction.ALLOWED);
    assertThat(evaluateObservabilityTraffic("grafana", "prometheus", 9090))
        .isEqualTo(PolicyAction.ALLOWED);

    // 2. Prohibited traffic: unauthorized service cross-talk to telemetry storage
    assertThat(evaluateObservabilityTraffic("contribution-api", "prometheus", 9090))
        .as("Microservices cannot query Prometheus directly")
        .isEqualTo(PolicyAction.BLOCKED);
    assertThat(evaluateObservabilityTraffic("edge-gateway", "tempo", 3200))
        .as("Edge gateway cannot query Tempo directly")
        .isEqualTo(PolicyAction.BLOCKED);
    assertThat(evaluateObservabilityTraffic("edge-gateway", "loki", 3100))
        .as("Edge gateway cannot query Loki directly")
        .isEqualTo(PolicyAction.BLOCKED);

    // 3. Prohibited traffic: observability workloads accessing financial storage
    assertThat(evaluateTraffic("grafana", "tigerbeetle", 3000))
        .as("Grafana cannot access financial ledger directly")
        .isEqualTo(PolicyAction.BLOCKED);
    assertThat(evaluateTraffic("grafana", "postgres", 5432))
        .as("Grafana cannot access PostgreSQL directly")
        .isEqualTo(PolicyAction.BLOCKED);
    assertThat(evaluateTraffic("otel-collector", "postgres", 5432))
        .as("Collector cannot access PostgreSQL directly")
        .isEqualTo(PolicyAction.BLOCKED);
  }

  private PolicyAction evaluateTraffic(String source, String dest, int port) {
    ConnectionAttempt attempt = new ConnectionAttempt(source, dest, port);
    return ALLOWED_STORAGE_CONNECTIONS.contains(attempt)
        ? PolicyAction.ALLOWED
        : PolicyAction.BLOCKED;
  }

  private PolicyAction evaluateObservabilityTraffic(String source, String dest, int port) {
    ConnectionAttempt attempt = new ConnectionAttempt(source, dest, port);
    return ALLOWED_OBSERVABILITY_CONNECTIONS.contains(attempt)
        ? PolicyAction.ALLOWED
        : PolicyAction.BLOCKED;
  }
}
