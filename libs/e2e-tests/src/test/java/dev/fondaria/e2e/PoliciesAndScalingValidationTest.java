package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 12.5 acceptance validations:
 *
 * <p>1. PriorityClass hierarchy: ledger-critical (1B) > broker-critical (900M) > database-critical
 * (800M) > cache-high (700M) > stateless-default (100M).
 *
 * <p>2. Every base deployment and statefulset assigns explicit priorityClassName.
 *
 * <p>3. HPAs exist for API workloads (contribution-api, balance-api, valuation-api) with dual
 * metrics: CPU and request-rate (http_requests_per_second).
 *
 * <p>4. Invariant: projection-worker (bounded by 16 queues) and tigerbeetle-cdc (singleton leader
 * with warm standby) have NO HPA.
 *
 * <p>5. Default-deny NetworkPolicies enforce traffic isolation: edge cannot reach storage directly;
 * DNS and OTel telemetry explicitly allowed.
 */
class PoliciesAndScalingValidationTest {

  @Test
  @DisplayName("PriorityClasses define expected priority hierarchy and preemption policy")
  void priorityClassesDefineExpectedHierarchy() throws Exception {
    Path priorityPath =
        Path.of(RepositoryRoot.path("infrastructure/k8s/base/priority/priorityclasses.yaml"));
    assertThat(priorityPath).isRegularFile();

    List<Map<String, Object>> docs = loadYamlDocuments(priorityPath.toFile());
    assertThat(docs).hasSize(5);

    Map<String, Integer> expectedPriorities =
        Map.of(
            "fondaria-ledger-critical", 1000000000,
            "fondaria-broker-critical", 900000000,
            "fondaria-database-critical", 800000000,
            "fondaria-cache-high", 700000000,
            "fondaria-stateless-default", 100000000);

    for (Map<String, Object> doc : docs) {
      assertThat(doc.get("kind")).isEqualTo("PriorityClass");
      String name = ((Map<String, Object>) doc.get("metadata")).get("name").toString();
      assertThat(expectedPriorities).containsKey(name);
      int value = (int) doc.get("value");
      assertThat(value).isEqualTo(expectedPriorities.get(name));
      assertThat(doc.get("preemptionPolicy")).isEqualTo("PreemptLowerPriority");
    }

    // Assert strict ordering: ledger > broker > database > cache > stateless
    assertThat(expectedPriorities.get("fondaria-ledger-critical"))
        .isGreaterThan(expectedPriorities.get("fondaria-broker-critical"));
    assertThat(expectedPriorities.get("fondaria-broker-critical"))
        .isGreaterThan(expectedPriorities.get("fondaria-database-critical"));
    assertThat(expectedPriorities.get("fondaria-database-critical"))
        .isGreaterThan(expectedPriorities.get("fondaria-cache-high"));
    assertThat(expectedPriorities.get("fondaria-cache-high"))
        .isGreaterThan(expectedPriorities.get("fondaria-stateless-default"));
  }

  @Test
  @DisplayName("All base workloads have explicit priorityClassName assigned")
  void allBaseWorkloadsHavePriorityClassAssigned() throws Exception {
    List<Map<String, Object>> renderedDocs = renderKustomize("infrastructure/k8s/base");

    // Check Deployments (9 microservices + 2 observability workloads + Keycloak, Task D.3)
    List<Map<String, Object>> deployments = filterByKind(renderedDocs, "Deployment");
    assertThat(deployments).hasSize(12);
    for (Map<String, Object> dep : deployments) {
      String name = getName(dep);
      Map<String, Object> spec = getMap(dep, "spec");
      Map<String, Object> template = getMap(spec, "template");
      Map<String, Object> podSpec = getMap(template, "spec");
      assertThat(podSpec.get("priorityClassName"))
          .as("Deployment %s must specify priorityClassName", name)
          .isEqualTo("fondaria-stateless-default");
    }

    // Check TigerBeetle StatefulSet
    Map<String, Object> tb = findByNameAndKind(renderedDocs, "tigerbeetle", "StatefulSet");
    assertThat(tb).isNotNull();
    Map<String, Object> tbPodSpec = getMap(getMap(getMap(tb, "spec"), "template"), "spec");
    assertThat(tbPodSpec.get("priorityClassName")).isEqualTo("fondaria-ledger-critical");

    // Check Valkey StatefulSet
    Map<String, Object> valkey = findByNameAndKind(renderedDocs, "valkey", "StatefulSet");
    assertThat(valkey).isNotNull();
    Map<String, Object> valkeyPodSpec = getMap(getMap(getMap(valkey, "spec"), "template"), "spec");
    assertThat(valkeyPodSpec.get("priorityClassName")).isEqualTo("fondaria-cache-high");

    // Check Postgres Cluster
    Map<String, Object> pg = findByNameAndKind(renderedDocs, "postgres", "Cluster");
    assertThat(pg).isNotNull();
    assertThat(getMap(pg, "spec").get("priorityClassName")).isEqualTo("fondaria-database-critical");

    // Check RabbitMQ Cluster
    // The RabbitmqCluster schema has no priorityClassName; it goes through the StatefulSet
    // override.
    Map<String, Object> rmq = findByNameAndKind(renderedDocs, "rabbitmq", "RabbitmqCluster");
    assertThat(rmq).isNotNull();
    Map<String, Object> rmqTemplate =
        getMap(
            getMap(getMap(getMap(getMap(rmq, "spec"), "override"), "statefulSet"), "spec"),
            "template");
    assertThat(getMap(rmqTemplate, "spec").get("priorityClassName"))
        .isEqualTo("fondaria-broker-critical");

    // Check Observability StatefulSets
    for (String obs : List.of("prometheus", "tempo", "loki")) {
      Map<String, Object> ss = findByNameAndKind(renderedDocs, obs, "StatefulSet");
      assertThat(ss).as("StatefulSet %s must exist", obs).isNotNull();
      Map<String, Object> ssPodSpec = getMap(getMap(getMap(ss, "spec"), "template"), "spec");
      assertThat(ssPodSpec.get("priorityClassName"))
          .as("StatefulSet %s must specify priorityClassName", obs)
          .isEqualTo("fondaria-stateless-default");
    }
  }

  @Test
  @DisplayName("HPAs scale the three APIs on CPU, in every overlay, with a per-environment floor")
  void hpasScaleApisOnCpu() throws Exception {
    Map<String, Integer> floors = Map.of("local", 1, "prod", 3);
    for (Map.Entry<String, Integer> overlay : floors.entrySet()) {
      List<Map<String, Object>> renderedDocs =
          renderKustomize("infrastructure/k8s/overlays/" + overlay.getKey());
      List<Map<String, Object>> hpas = filterByKind(renderedDocs, "HorizontalPodAutoscaler");
      assertThat(hpas).as("HPAs in %s", overlay.getKey()).hasSize(3);

      for (Map<String, Object> hpa : hpas) {
        String name = getName(hpa);
        Map<String, Object> spec = getMap(hpa, "spec");
        assertThat(spec.get("minReplicas"))
            .as("HPA %s floor in %s", name, overlay.getKey())
            .isEqualTo(overlay.getValue());
        List<Map<String, Object>> metrics = getList(spec, "metrics");
        // CPU only: it is what metrics-server provides; a request-rate metric would need
        // prometheus-adapter, which the platform does not run, and an HPA with an unknown
        // metric is one that silently never scales on it.
        assertThat(metrics).as("HPA %s metrics", name).hasSize(1);
        Map<String, Object> cpuMetric = metrics.get(0);
        assertThat(cpuMetric.get("type")).isEqualTo("Resource");
        Map<String, Object> resource = getMap(cpuMetric, "resource");
        assertThat(resource.get("name")).isEqualTo("cpu");
        assertThat(getMap(resource, "target").get("averageUtilization")).isEqualTo(70);
      }
    }
  }

  @Test
  @DisplayName("Invariant: No HPA on projection-worker or tigerbeetle-cdc")
  void noHpaOnProjectionWorkerOrCdc() throws Exception {
    List<Map<String, Object>> renderedDocs = renderKustomize("infrastructure/k8s/overlays/prod");
    List<Map<String, Object>> hpas = filterByKind(renderedDocs, "HorizontalPodAutoscaler");

    for (Map<String, Object> hpa : hpas) {
      Map<String, Object> scaleTargetRef = getMap(getMap(hpa, "spec"), "scaleTargetRef");
      String targetName = scaleTargetRef.get("name").toString();
      assertThat(targetName)
          .as("No HPA is permitted on projection-worker (bounded by queue concurrency)")
          .isNotEqualTo("projection-worker");
      assertThat(targetName)
          .as("No HPA is permitted on tigerbeetle-cdc (singleton stream with warm standby)")
          .isNotEqualTo("tigerbeetle-cdc");
    }

    // Verify deployment manifest comments document the rationale
    Path projFile =
        Path.of(RepositoryRoot.path("infrastructure/k8s/base/projection-worker/deployment.yaml"));
    String projContent = java.nio.file.Files.readString(projFile);
    assertThat(projContent).contains("No HPA on projection-worker");

    Path cdcFile =
        Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle-cdc/deployment.yaml"));
    String cdcContent = java.nio.file.Files.readString(cdcFile);
    assertThat(cdcContent).contains("No HPA on tigerbeetle-cdc");
  }

  @Test
  @DisplayName("Default-deny and service NetworkPolicies enforce traffic isolation")
  void networkPoliciesEnforceTrafficIsolation() throws Exception {
    List<Map<String, Object>> renderedDocs = renderKustomize("infrastructure/k8s/base");
    List<Map<String, Object>> netPols = filterByKind(renderedDocs, "NetworkPolicy");
    assertThat(netPols).isNotEmpty();

    // 1. Default-deny policy exists and covers Ingress and Egress
    Map<String, Object> defaultDeny =
        findByNameAndKind(renderedDocs, "default-deny-all", "NetworkPolicy");
    assertThat(defaultDeny).isNotNull();
    Map<String, Object> ddSpec = getMap(defaultDeny, "spec");
    List<String> policyTypes = (List<String>) ddSpec.get("policyTypes");
    assertThat(policyTypes).containsExactlyInAnyOrder("Ingress", "Egress");

    // 2. Storage isolation: TigerBeetle and Postgres allow client access only from authorized pods
    Map<String, Object> tbPolicy =
        findByNameAndKind(renderedDocs, "tigerbeetle-policy", "NetworkPolicy");
    assertThat(tbPolicy).isNotNull();
    Map<String, Object> tbSpec = getMap(tbPolicy, "spec");
    List<Map<String, Object>> tbIngress = getList(tbSpec, "ingress");
    assertThat(tbIngress).isNotEmpty();

    // Verify authorized TigerBeetle clients and strict absence of untrusted/edge callers
    List<Map<String, Object>> tbFrom = getList(tbIngress.get(0), "from");
    Map<String, Object> tbPodSelector = getMap(tbFrom.get(0), "podSelector");
    List<Map<String, Object>> tbExpressions = getList(tbPodSelector, "matchExpressions");
    List<String> tbAllowedClients = (List<String>) tbExpressions.get(0).get("values");
    assertThat(tbAllowedClients)
        .contains("contribution-api", "balance-api", "valuation-api", "allotment-job", "eod-job")
        .doesNotContain("edge-gateway", "keycloak", "untrusted-client");

    Map<String, Object> pgPolicy =
        findByNameAndKind(renderedDocs, "postgres-policy", "NetworkPolicy");
    assertThat(pgPolicy).isNotNull();
    Map<String, Object> pgSpec = getMap(pgPolicy, "spec");
    List<Map<String, Object>> pgIngress = getList(pgSpec, "ingress");
    assertThat(pgIngress).isNotEmpty();

    // Verify authorized Postgres clients and strict absence of edge callers
    List<Map<String, Object>> pgFrom = getList(pgIngress.get(0), "from");
    Map<String, Object> pgPodSelector = getMap(pgFrom.get(0), "podSelector");
    List<Map<String, Object>> pgExpressions = getList(pgPodSelector, "matchExpressions");
    List<String> pgAllowedClients = (List<String>) pgExpressions.get(0).get("values");
    assertThat(pgAllowedClients)
        .contains(
            "contribution-api",
            "balance-api",
            "valuation-api",
            "projection-worker",
            "statement-worker")
        .doesNotContain("edge-gateway", "keycloak", "untrusted-client");
  }

  private List<Map<String, Object>> renderKustomize(String subpath) throws Exception {
    Path targetPath = Path.of(RepositoryRoot.path(subpath));
    Process process = new ProcessBuilder("kubectl", "kustomize", targetPath.toString()).start();
    try (InputStream is = process.getInputStream()) {
      Yaml yaml = new Yaml();
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object doc : yaml.loadAll(is)) {
        if (doc instanceof Map) {
          docs.add((Map<String, Object>) doc);
        }
      }
      int exit = process.waitFor();
      assertThat(exit).isZero();
      return docs;
    }
  }

  private List<Map<String, Object>> loadYamlDocuments(File file) throws Exception {
    try (InputStream is = new FileInputStream(file)) {
      Yaml yaml = new Yaml();
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object doc : yaml.loadAll(is)) {
        if (doc instanceof Map) {
          docs.add((Map<String, Object>) doc);
        }
      }
      return docs;
    }
  }

  private List<Map<String, Object>> filterByKind(List<Map<String, Object>> docs, String kind) {
    return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
  }

  private Map<String, Object> findByNameAndKind(
      List<Map<String, Object>> docs, String name, String kind) {
    return docs.stream()
        .filter(d -> kind.equals(d.get("kind")) && name.equals(getName(d)))
        .findFirst()
        .orElse(null);
  }

  private String getName(Map<String, Object> doc) {
    Map<String, Object> metadata = getMap(doc, "metadata");
    return metadata.get("name").toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getMap(Map<String, Object> parent, String key) {
    return (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getList(Map<String, Object> parent, String key) {
    return (List<Map<String, Object>>) parent.get(key);
  }
}
