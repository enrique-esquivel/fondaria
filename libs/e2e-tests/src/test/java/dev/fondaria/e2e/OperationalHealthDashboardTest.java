package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.testing.ContainerImages;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Structure of {@code dashboards/operational-health.json} and {@code
 * alerts/operational-health.rules.yml}, as {@link FinancialControlDashboardTest} does for its own
 * dashboard: provisioning requirements, one panel per required view, a description of what "bad"
 * looks like on every panel, and a grid without overlaps.
 *
 * <p>Where the dashboard names platform objects -- the Spring services, the setup Jobs -- the
 * expected names come from the manifests, so a new service or Job that the dashboard misses fails
 * here rather than going unwatched.
 */
class OperationalHealthDashboardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String RUNBOOK_ANCHOR = "operational-health-dashboard";

  @Test
  @DisplayName("operational-health.json is provisionable and every panel says what bad looks like")
  void dashboardStructure() throws Exception {
    JsonNode root = dashboard();

    assertThat(root.get("uid").asText()).isEqualTo("operational-health");
    assertThat(root.get("editable").asBoolean()).isFalse();
    assertThat(root.get("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);

    record Rect(String title, int x1, int y1, int x2, int y2) {
      boolean overlaps(Rect other) {
        return x1 < other.x2 && x2 > other.x1 && y1 < other.y2 && y2 > other.y1;
      }
    }
    List<Rect> rects = new ArrayList<>();
    for (JsonNode panel : root.get("panels")) {
      String title = panel.get("title").asText();
      assertThat(panel.get("datasource").get("uid").asText()).as(title).isEqualTo("prometheus");
      assertThat(panel.get("targets").isEmpty()).as("%s has no query", title).isFalse();
      for (JsonNode target : panel.get("targets")) {
        assertThat(target.get("datasource").get("uid").asText()).as(title).isEqualTo("prometheus");
        assertThat(target.get("expr").asText()).as(title).isNotBlank();
      }
      assertThat(panel.get("description").asText())
          .as("%s must describe what bad looks like", title)
          .contains("Bad:");

      JsonNode pos = panel.get("gridPos");
      int x = pos.get("x").asInt();
      int w = pos.get("w").asInt();
      assertThat(x + w).as(title).isLessThanOrEqualTo(24);
      Rect rect =
          new Rect(
              title, x, pos.get("y").asInt(), x + w, pos.get("y").asInt() + pos.get("h").asInt());
      for (Rect other : rects) {
        assertThat(rect.overlaps(other)).as("%s overlaps %s", title, other.title()).isFalse();
      }
      rects.add(rect);
    }
  }

  @Test
  @DisplayName("operational-health.json has a panel for every view the task requires")
  void requiredViewsArePresent() throws Exception {
    List<String> exprs = expressions(dashboard());

    // Desired vs ready replicas per workload
    assertThat(exprs).anyMatch(e -> e.contains("kube_deployment_spec_replicas"));
    assertThat(exprs).anyMatch(e -> e.contains("kube_deployment_status_replicas_ready"));
    assertThat(exprs).anyMatch(e -> e.contains("kube_statefulset_replicas"));
    assertThat(exprs).anyMatch(e -> e.contains("kube_statefulset_status_replicas_ready"));
    // Pods not Ready / Pending / CrashLoopBackOff
    assertThat(exprs).anyMatch(e -> e.contains("kube_pod_status_ready"));
    assertThat(exprs).anyMatch(e -> e.contains("phase=\"Pending\""));
    assertThat(exprs).anyMatch(e -> e.contains("reason=\"CrashLoopBackOff\""));
    // Restarts and OOMKilled in the window
    assertThat(exprs)
        .anyMatch(
            e -> e.contains("kube_pod_container_status_restarts_total") && e.contains("$__range"));
    assertThat(exprs).anyMatch(e -> e.contains("reason=\"OOMKilled\""));
    // Node count and readiness per zone
    assertThat(exprs)
        .anyMatch(
            e ->
                e.contains("kube_node_status_condition")
                    && e.contains("label_topology_kubernetes_io_zone"));
    // CloudNativePG instances and replication lag
    assertThat(exprs).anyMatch(e -> e.contains("cnpg_collector_up"));
    assertThat(exprs).anyMatch(e -> e.contains("cnpg_pg_replication_lag"));
    // RabbitMQ nodes, depth and consumers for bridge.ledger, projection.entries.v1.* and DLQs
    assertThat(exprs).anyMatch(e -> e.contains("up{job=\"rabbitmq\"}"));
    assertThat(exprs)
        .anyMatch(
            e ->
                e.contains("rabbitmq_detailed_queue_messages")
                    && e.contains("bridge\\\\.ledger")
                    && e.contains("projection\\\\.entries\\\\.v1")
                    && e.contains("\\\\.dlq"));
    assertThat(exprs)
        .anyMatch(
            e ->
                e.contains("rabbitmq_detailed_queue_consumers")
                    && e.contains("bridge\\\\.ledger")
                    && e.contains("projection\\\\.entries\\\\.v1"));
    // TigerBeetle replicas
    assertThat(exprs).anyMatch(e -> e.contains("statefulset=\"tigerbeetle\""));
  }

  @Test
  @DisplayName("Every Spring service with a readiness probe is on the service-readiness panel")
  void everySpringServiceIsOnTheReadinessPanel() throws Exception {
    Path base = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    List<String> springServices = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(base)) {
      for (Path deployment :
          dirs.map(d -> d.resolve("deployment.yaml")).filter(Files::isRegularFile).toList()) {
        if (Files.readString(deployment).contains("path: /actuator/health/readiness")) {
          springServices.add(deployment.getParent().getFileName().toString());
        }
      }
    }
    assertThat(springServices)
        .as("Spring services found in k8s/base")
        .hasSizeGreaterThanOrEqualTo(8);

    String readiness =
        panelExpressions(dashboard(), "Service readiness (Spring /actuator/health/readiness)")
            .get(0);
    for (String service : springServices) {
      assertThat(readiness).as("readiness panel covers %s", service).contains(service);
    }
  }

  @Test
  @DisplayName("Every setup Job in k8s/base is matched by the Setup Jobs panel and the Job alerts")
  void everySetupJobIsWatched() throws Exception {
    List<String> jobs = new ArrayList<>();
    Yaml yaml = new Yaml();
    try (Stream<Path> files = Files.walk(Path.of(RepositoryRoot.path("infrastructure/k8s/base")))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).toList()) {
        try (InputStream in = Files.newInputStream(file)) {
          for (Object doc : yaml.loadAll(in)) {
            if (doc instanceof Map<?, ?> map && "Job".equals(map.get("kind"))) {
              jobs.add((String) ((Map<?, ?>) map.get("metadata")).get("name"));
            }
          }
        }
      }
    }
    assertThat(jobs).contains("rabbitmq-topology", "object-store-buckets");

    String setupJobs = panelExpressions(dashboard(), "Setup Jobs").get(0);
    String rules = Files.readString(rulesPath());
    for (String job : jobs) {
      assertThat(setupJobs).as("Setup Jobs panel matches %s", job).contains(job);
      assertThat(rules).as("Job alerts match %s", job).contains(job);
    }
    // CloudNativePG's own initdb/join Jobs are named after the cluster.
    assertThat(setupJobs).contains("postgres-.*");
  }

  @Test
  @DisplayName("Every operational-health alert links to the runbook section that exists")
  void everyAlertLinksToTheRunbook() throws Exception {
    Map<String, Object> root;
    try (InputStream in = Files.newInputStream(rulesPath())) {
      root = new Yaml().load(in);
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
    int alerts = 0;
    for (Map<String, Object> group : groups) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
      for (Map<String, Object> rule : rules) {
        @SuppressWarnings("unchecked")
        Map<String, String> annotations = (Map<String, String>) rule.get("annotations");
        assertThat(annotations.get("runbook_url"))
            .as("%s runbook_url", rule.get("alert"))
            .isEqualTo("docs/RUNBOOKS.md#" + RUNBOOK_ANCHOR);
        alerts++;
      }
    }
    assertThat(alerts).isGreaterThanOrEqualTo(4);
    assertThat(Files.readString(Path.of(RepositoryRoot.path("docs/RUNBOOKS.md"))))
        .contains("<a id=\"" + RUNBOOK_ANCHOR + "\"></a>");
  }

  @Test
  @DisplayName(
      "The kube-state-metrics Deployment runs the release the metric-name test runs, by digest")
  void kubeStateMetricsImageMatchesTheTestedRelease() throws Exception {
    String deployment =
        Files.readString(
            Path.of(
                RepositoryRoot.path(
                    "infrastructure/k8s/base/observability/kube-state-metrics/deployment.yaml")));
    assertThat(deployment).contains("image: " + ContainerImages.KUBE_STATE_METRICS + "@sha256:");
  }

  private static JsonNode dashboard() throws Exception {
    try (InputStream in =
        Files.newInputStream(
            Path.of(
                RepositoryRoot.path(
                    "infrastructure/observability/dashboards/operational-health.json")))) {
      return MAPPER.readTree(in);
    }
  }

  private static Path rulesPath() {
    return Path.of(
        RepositoryRoot.path("infrastructure/observability/alerts/operational-health.rules.yml"));
  }

  private static List<String> expressions(JsonNode root) {
    List<String> exprs = new ArrayList<>();
    for (JsonNode panel : root.get("panels")) {
      for (JsonNode target : panel.get("targets")) {
        exprs.add(target.get("expr").asText());
      }
    }
    return exprs;
  }

  private static List<String> panelExpressions(JsonNode root, String title) {
    for (JsonNode panel : root.get("panels")) {
      if (title.equals(panel.get("title").asText())) {
        List<String> exprs = new ArrayList<>();
        panel.get("targets").forEach(t -> exprs.add(t.get("expr").asText()));
        return exprs;
      }
    }
    throw new AssertionError("no panel titled " + title);
  }
}
