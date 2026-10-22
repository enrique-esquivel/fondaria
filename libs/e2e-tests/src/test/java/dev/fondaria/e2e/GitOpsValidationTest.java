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
 * Task 12.7 acceptance validations:
 *
 * <p>1. ArgoCD Application manifests exist for both environments (local, prod) with proper
 * repository paths, destinations, and sync policies.
 *
 * <p>2. Automated sync with prune and self-heal is enabled for local overlay, while prod requires
 * manual sync approval (no automated block).
 *
 * <p>3. Sync wave annotations strictly enforce startup dependency ordering:
 *
 * <ul>
 *   <li>Wave 0: Priority classes, operators, certificates, secrets
 *   <li>Wave 1: Storage and data infrastructure (TigerBeetle, PostgreSQL, RabbitMQ, Valkey)
 *   <li>Wave 2: Stateless background workers and stream processors (amqp-fanout, projection-worker,
 *       allotment-job, statement-worker, eod-job, tigerbeetle-cdc)
 *   <li>Wave 3: Public APIs and edge ingress (contribution-api, balance-api, valuation-api)
 * </ul>
 *
 * <p>4. Argo Rollouts canary progressive delivery manifests exist for payment-api
 * (contribution-api) with traffic steps and automated Prometheus AnalysisTemplate evaluating
 * transfer_rejected_total rate and p99 latency SLO (< 250ms).
 */
class GitOpsValidationTest {

  @Test
  @DisplayName("ArgoCD Application manifests exist and specify valid sync policies")
  void argoCdApplicationsExistAndSpecifySyncPolicies() throws Exception {
    Path argocdDir = Path.of(RepositoryRoot.path("infrastructure/k8s/argocd"));
    assertThat(argocdDir.resolve("application-local.yaml")).isRegularFile();
    assertThat(argocdDir.resolve("application-prod.yaml")).isRegularFile();
    assertThat(argocdDir.resolve("kustomization.yaml")).isRegularFile();

    Yaml yaml = new Yaml();

    // 1. Local Application: automated sync, prune: true, selfHeal: true
    try (InputStream in =
        new FileInputStream(argocdDir.resolve("application-local.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> localApp = yaml.load(in);
      assertThat(localApp.get("apiVersion")).isEqualTo("argoproj.io/v1alpha1");
      assertThat(localApp.get("kind")).isEqualTo("Application");

      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) localApp.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> source = (Map<String, Object>) spec.get("source");
      assertThat(source.get("path")).isEqualTo("infrastructure/k8s/overlays/local");

      @SuppressWarnings("unchecked")
      Map<String, Object> syncPolicy = (Map<String, Object>) spec.get("syncPolicy");
      assertThat(syncPolicy).as("Local environment must define syncPolicy").isNotNull();

      @SuppressWarnings("unchecked")
      Map<String, Object> automated = (Map<String, Object>) syncPolicy.get("automated");
      assertThat(automated).as("Local environment must enable automated sync").isNotNull();
      assertThat(automated.get("prune")).isEqualTo(true);
      assertThat(automated.get("selfHeal")).isEqualTo(true);
    }

    // 2. Production Application: manual approval (no automated sync block)
    try (InputStream in =
        new FileInputStream(argocdDir.resolve("application-prod.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> prodApp = yaml.load(in);
      assertThat(prodApp.get("apiVersion")).isEqualTo("argoproj.io/v1alpha1");
      assertThat(prodApp.get("kind")).isEqualTo("Application");

      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) prodApp.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> source = (Map<String, Object>) spec.get("source");
      assertThat(source.get("path")).isEqualTo("infrastructure/k8s/overlays/prod");

      @SuppressWarnings("unchecked")
      Map<String, Object> syncPolicy = (Map<String, Object>) spec.get("syncPolicy");
      if (syncPolicy != null) {
        assertThat(syncPolicy.get("automated"))
            .as(
                "Production Application must NOT have automated sync enabled (manual approval required)")
            .isNull();
      }
    }
  }

  @Test
  @DisplayName("Sync waves enforce storage (Wave 1) before workers (Wave 2) and APIs (Wave 3)")
  void syncWavesEnforceDependencyOrdering() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));

    // Wave 1: Core storage and data infrastructure
    Map<String, String> wave1Workloads =
        Map.of(
            "tigerbeetle/statefulset.yaml", "tigerbeetle",
            "postgres/cluster.yaml", "postgres-cluster",
            "rabbitmq/rabbitmq-cluster.yaml", "rabbitmq-cluster",
            "valkey/valkey-cluster.yaml", "valkey");

    for (Map.Entry<String, String> entry : wave1Workloads.entrySet()) {
      File manifest = basePath.resolve(entry.getKey()).toFile();
      String wave = extractSyncWave(manifest);
      assertThat(wave)
          .as("Storage workload %s must be assigned to sync wave 1", entry.getValue())
          .isEqualTo("1");
    }

    // Wave 2: Stateless background workers, projection workers, CDC jobs
    Map<String, String> wave2Workloads =
        Map.of(
            "amqp-fanout/deployment.yaml", "amqp-fanout",
            "projection-worker/deployment.yaml", "projection-worker",
            "allotment-job/deployment.yaml", "allotment-job",
            "statement-worker/deployment.yaml", "statement-worker",
            "eod-job/deployment.yaml", "eod-job",
            "tigerbeetle-cdc/deployment.yaml", "tigerbeetle-cdc");

    for (Map.Entry<String, String> entry : wave2Workloads.entrySet()) {
      File manifest = basePath.resolve(entry.getKey()).toFile();
      String wave = extractSyncWave(manifest);
      assertThat(wave)
          .as("Worker workload %s must be assigned to sync wave 2", entry.getValue())
          .isEqualTo("2");
    }

    // Wave 3: Public APIs and edge ingress
    Map<String, String> wave3Workloads =
        Map.of(
            "contribution-api/deployment.yaml", "contribution-api",
            "balance-api/deployment.yaml", "balance-api",
            "valuation-api/deployment.yaml", "valuation-api");

    for (Map.Entry<String, String> entry : wave3Workloads.entrySet()) {
      File manifest = basePath.resolve(entry.getKey()).toFile();
      String wave = extractSyncWave(manifest);
      assertThat(wave)
          .as("Public API workload %s must be assigned to sync wave 3", entry.getValue())
          .isEqualTo("3");
    }

    // Wave 0: Priority classes and operators
    Path priorityFile = basePath.resolve("priority/priorityclasses.yaml");
    List<Map<String, Object>> priorityDocs = loadYamlDocuments(priorityFile.toFile());
    for (Map<String, Object> doc : priorityDocs) {
      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
      @SuppressWarnings("unchecked")
      Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
      assertThat(annotations.get("argocd.argoproj.io/sync-wave"))
          .as("PriorityClass %s must be in wave 0", metadata.get("name"))
          .isEqualTo("0");
    }
  }

  @Test
  @DisplayName(
      "Argo Rollouts canary and AnalysisTemplate are properly configured for contribution-api")
  void argoRolloutsCanaryAndAnalysisTemplateConfigured() throws Exception {
    Path rolloutsDir = Path.of(RepositoryRoot.path("infrastructure/k8s/argo-rollouts"));
    assertThat(rolloutsDir.resolve("rollout-contribution-api.yaml")).isRegularFile();
    assertThat(rolloutsDir.resolve("analysistemplate-payment-api.yaml")).isRegularFile();
    assertThat(rolloutsDir.resolve("services-canary.yaml")).isRegularFile();
    assertThat(rolloutsDir.resolve("kustomization.yaml")).isRegularFile();

    Yaml yaml = new Yaml();

    // Validate Rollout
    try (InputStream in =
        new FileInputStream(rolloutsDir.resolve("rollout-contribution-api.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> rollout = yaml.load(in);
      assertThat(rollout.get("apiVersion")).isEqualTo("argoproj.io/v1alpha1");
      assertThat(rollout.get("kind")).isEqualTo("Rollout");

      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) rollout.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> strategy = (Map<String, Object>) spec.get("strategy");
      @SuppressWarnings("unchecked")
      Map<String, Object> canary = (Map<String, Object>) strategy.get("canary");

      assertThat(canary.get("canaryService")).isEqualTo("contribution-api-canary");
      assertThat(canary.get("stableService")).isEqualTo("contribution-api");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> steps = (List<Map<String, Object>>) canary.get("steps");
      assertThat(steps).as("Canary steps must be defined").isNotEmpty();

      // Ensure steps specify setWeight and analysis
      boolean hasSetWeight = steps.stream().anyMatch(s -> s.containsKey("setWeight"));
      boolean hasAnalysis = steps.stream().anyMatch(s -> s.containsKey("analysis"));
      assertThat(hasSetWeight).as("Canary must define setWeight step").isTrue();
      assertThat(hasAnalysis).as("Canary must define analysis step").isTrue();
    }

    // Validate AnalysisTemplate
    try (InputStream in =
        new FileInputStream(rolloutsDir.resolve("analysistemplate-payment-api.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> template = yaml.load(in);
      assertThat(template.get("apiVersion")).isEqualTo("argoproj.io/v1alpha1");
      assertThat(template.get("kind")).isEqualTo("AnalysisTemplate");

      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) template.get("spec");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> metrics = (List<Map<String, Object>>) spec.get("metrics");
      assertThat(metrics).hasSize(2);

      // Metric 1: transfer rejection rate
      Map<String, Object> rejectionMetric = metrics.get(0);
      assertThat(rejectionMetric.get("name")).isEqualTo("transfer-rejection-rate");
      @SuppressWarnings("unchecked")
      Map<String, Object> rejProvider = (Map<String, Object>) rejectionMetric.get("provider");
      @SuppressWarnings("unchecked")
      Map<String, Object> rejProm = (Map<String, Object>) rejProvider.get("prometheus");
      assertThat((String) rejProm.get("query")).contains("transfer_rejected_total");
      assertThat(rejectionMetric.get("successCondition")).isEqualTo("result[0] <= 0.02");

      // Metric 2: p99 latency
      Map<String, Object> latencyMetric = metrics.get(1);
      assertThat(latencyMetric.get("name")).isEqualTo("payment-latency-p99");
      @SuppressWarnings("unchecked")
      Map<String, Object> latProvider = (Map<String, Object>) latencyMetric.get("provider");
      @SuppressWarnings("unchecked")
      Map<String, Object> latProm = (Map<String, Object>) latProvider.get("prometheus");
      assertThat((String) latProm.get("query")).contains("http_server_requests_seconds_bucket");
      assertThat(latencyMetric.get("successCondition")).isEqualTo("result[0] <= 0.25");
    }
  }

  private String extractSyncWave(File manifest) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(manifest)) {
      for (Object docObj : yaml.loadAll(in)) {
        if (!(docObj instanceof Map)) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) docObj;
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
        if (metadata == null) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
        if (annotations == null) continue;
        Object wave = annotations.get("argocd.argoproj.io/sync-wave");
        if (wave != null) return wave.toString();
      }
      return null;
    }
  }

  private List<Map<String, Object>> loadYamlDocuments(File file) throws Exception {
    Yaml yaml = new Yaml();
    List<Map<String, Object>> documents = new ArrayList<>();
    try (InputStream in = new FileInputStream(file)) {
      for (Object doc : yaml.loadAll(in)) {
        if (doc instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> map = (Map<String, Object>) doc;
          documents.add(map);
        }
      }
    }
    return documents;
  }
}
