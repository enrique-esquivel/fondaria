package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.testing.ContainerImages;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.yaml.snakeyaml.Yaml;

/**
 * Every metric {@code dashboards/operational-health.json} and {@code
 * alerts/operational-health.rules.yml} read is a series the platform's exporters really emit, as
 * {@link MetricNamesAreRealIntegrationTest} does for the Micrometer metrics.
 *
 * <p>The exporters run for real, configured from the repository's own files rather than from values
 * copied into this test:
 *
 * <ul>
 *   <li>kube-state-metrics, with the exact args of {@code
 *       k8s/base/observability/kube-state-metrics/deployment.yaml}, against a k3s API server. Its
 *       {@code --resources} and {@code --metric-labels-allowlist} flags decide which families
 *       exist, so a panel reading a resource the Deployment does not enable fails here.
 *   <li>RabbitMQ, scraped with the {@code family} params of {@code
 *       observability/prometheus/scrape.d/rabbitmq.yml}.
 * </ul>
 *
 * <p>CloudNativePG's instance manager cannot run outside a CNPG cluster, so its names are the
 * allowlist below, each one from the pinned release (bootstrap/versions.env CNPG_VERSION 1.25.2):
 * {@code cnpg_collector_up} is the instance manager's built-in collector, the {@code
 * cnpg_pg_replication_*} gauges are the {@code pg_replication} query of the {@code
 * cnpg-default-monitoring} ConfigMap that release ships, enabled by default and not disabled by
 * postgres/cluster.yaml. {@code up} is Prometheus's own per-target series.
 */
@Tag("integration")
class OperationalHealthMetricNamesAreRealIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> CLOUDNATIVE_PG_METRICS =
      Set.of(
          "cnpg_collector_up",
          "cnpg_pg_replication_lag",
          "cnpg_pg_replication_in_recovery",
          "cnpg_pg_replication_streaming_replicas");

  private static final Set<String> PROMETHEUS_SYNTHETIC = Set.of("up");

  private static final String RABBITMQ_USER = "fondaria";
  private static final String RABBITMQ_PASSWORD = "fondaria-test";

  @Test
  @DisplayName(
      "Every metric in the operational-health dashboard and rules is emitted by kube-state-metrics,"
          + " RabbitMQ or CloudNativePG")
  void everyReferencedMetricIsReallyEmitted() throws Exception {
    Set<String> emitted = new HashSet<>();
    emitted.addAll(kubeStateMetricsNames());
    emitted.addAll(rabbitMqNames());

    // Sanity: the exporters produced the families the dashboard is built on, so an empty scrape
    // cannot pass the loop below by accident.
    assertThat(emitted)
        .contains(
            "kube_deployment_spec_replicas",
            "kube_statefulset_status_replicas_ready",
            "kube_pod_container_status_waiting_reason",
            "kube_job_failed",
            "kube_node_labels",
            "rabbitmq_detailed_queue_messages",
            "rabbitmq_detailed_queue_consumers");

    List<String[]> expressions = new ArrayList<>();
    collectDashboardExpressions(
        Path.of(
            RepositoryRoot.path("infrastructure/observability/dashboards/operational-health.json")),
        expressions);
    collectRuleExpressions(
        Path.of(
            RepositoryRoot.path(
                "infrastructure/observability/alerts/operational-health.rules.yml")),
        expressions);
    assertThat(expressions).hasSizeGreaterThan(30);

    Set<String> unknown = new TreeSet<>();
    for (String[] expr : expressions) {
      for (String metric : MetricNamesAreRealIntegrationTest.extractMetricNames(expr[1])) {
        if (!emitted.contains(metric)
            && !CLOUDNATIVE_PG_METRICS.contains(metric)
            && !PROMETHEUS_SYNTHETIC.contains(metric)) {
          unknown.add(metric + "  (" + expr[0] + ": " + expr[1] + ")");
        }
      }
    }
    assertThat(unknown).as("metrics no exporter emits").isEmpty();
  }

  /**
   * One object of each namespaced kind deployment.yaml's --resources enables, in a namespace its
   * --namespaces watches. kube-state-metrics writes a family only once its store holds an object,
   * so an empty namespace would list none of them. Their pods never need to run: every family is
   * listed from the object as soon as it exists, whatever state it is in.
   */
  private static final String WATCHED_OBJECTS =
      """
      apiVersion: v1
      kind: Namespace
      metadata: {name: fondaria}
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata: {name: amqp-fanout, namespace: fondaria}
      spec:
        replicas: 1
        selector: {matchLabels: {app: amqp-fanout}}
        template:
          metadata: {labels: {app: amqp-fanout}}
          spec:
            containers: [{name: amqp-fanout, image: busybox:1.36}]
      ---
      apiVersion: apps/v1
      kind: StatefulSet
      metadata: {name: tigerbeetle, namespace: fondaria}
      spec:
        serviceName: tigerbeetle
        replicas: 1
        selector: {matchLabels: {app: tigerbeetle}}
        template:
          metadata: {labels: {app: tigerbeetle}}
          spec:
            containers: [{name: tigerbeetle, image: busybox:1.36}]
      ---
      apiVersion: batch/v1
      kind: Job
      metadata: {name: rabbitmq-topology, namespace: fondaria}
      spec:
        template:
          spec:
            restartPolicy: Never
            containers: [{name: topology, image: busybox:1.36}]
      """;

  /**
   * Starts k3s with {@link #WATCHED_OBJECTS} and kube-state-metrics with deployment.yaml's own
   * args, and returns every metric name on its /metrics.
   */
  private static Set<String> kubeStateMetricsNames() throws Exception {
    List<String> args = new ArrayList<>(kubeStateMetricsArgs());
    args.add("--kubeconfig=/etc/kube-state-metrics/kubeconfig");

    try (Network network = Network.newNetwork();
        GenericContainer<?> k3s =
            new GenericContainer<>(ContainerImages.K3S)
                .withPrivilegedMode(true)
                .withNetwork(network)
                .withNetworkAliases("k3s")
                .withCommand(
                    "server", "--disable=traefik", "--disable=metrics-server", "--tls-san=k3s")
                .waitingFor(
                    Wait.forLogMessage(".*Node controller sync successful.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {
      k3s.start();
      Container.ExecResult kubeconfig = k3s.execInContainer("cat", "/etc/rancher/k3s/k3s.yaml");
      assertThat(kubeconfig.getExitCode()).isZero();
      String inNetwork =
          kubeconfig.getStdout().replace("https://127.0.0.1:6443", "https://k3s:6443");
      k3s.copyFileToContainer(Transferable.of(WATCHED_OBJECTS), "/tmp/watched.yaml");
      Container.ExecResult applied =
          k3s.execInContainer("kubectl", "apply", "-f", "/tmp/watched.yaml");
      assertThat(applied.getExitCode()).as(applied.getStderr()).isZero();

      try (GenericContainer<?> ksm =
          new GenericContainer<>(ContainerImages.KUBE_STATE_METRICS)
              .withNetwork(network)
              .withCopyToContainer(
                  Transferable.of(inNetwork, 0644), "/etc/kube-state-metrics/kubeconfig")
              .withCommand(args.toArray(String[]::new))
              .withExposedPorts(8080, 8081)
              .waitingFor(
                  Wait.forHttp("/readyz")
                      .forPort(8081)
                      .forStatusCode(200)
                      .withStartupTimeout(Duration.ofMinutes(2)))) {
        ksm.start();
        String body = get("http://" + ksm.getHost() + ":" + ksm.getMappedPort(8080) + "/metrics");
        return metricNames(body);
      }
    }
  }

  private static List<String> kubeStateMetricsArgs() throws Exception {
    Path deployment =
        Path.of(
            RepositoryRoot.path(
                "infrastructure/k8s/base/observability/kube-state-metrics/deployment.yaml"));
    try (InputStream in = Files.newInputStream(deployment)) {
      Map<String, Object> doc = new Yaml().load(in);
      Map<String, Object> podSpec = map(map(map(doc, "spec"), "template"), "spec");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
      @SuppressWarnings("unchecked")
      List<String> args = (List<String>) containers.get(0).get("args");
      assertThat(args).as("kube-state-metrics args").anyMatch(a -> a.startsWith("--resources="));
      return args;
    }
  }

  /**
   * Starts RabbitMQ, declares one quorum queue so the per-queue families carry a sample, and
   * scrapes /metrics/detailed with the compose scrape file's own params.
   */
  @SuppressWarnings("unchecked")
  private static Set<String> rabbitMqNames() throws Exception {
    Map<String, Object> scrape;
    try (InputStream in =
        Files.newInputStream(
            Path.of(
                RepositoryRoot.path(
                    "infrastructure/observability/prometheus/scrape.d/rabbitmq.yml")))) {
      scrape =
          ((List<Map<String, Object>>)
                  new Yaml().<Map<String, Object>>load(in).get("scrape_configs"))
              .get(0);
    }
    String path = (String) scrape.get("metrics_path");
    List<String> families = ((Map<String, List<String>>) scrape.get("params")).get("family");
    StringBuilder query = new StringBuilder();
    for (String family : families) {
      query.append(query.isEmpty() ? "?" : "&").append("family=").append(family);
    }

    try (GenericContainer<?> rabbit =
        new GenericContainer<>(ContainerImages.RABBITMQ)
            .withEnv("RABBITMQ_DEFAULT_USER", RABBITMQ_USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", RABBITMQ_PASSWORD)
            .withExposedPorts(15672, 15692)
            // The management API, not /metrics: the Prometheus plugin answers before the
            // management plugin that declares the queue below is up.
            .waitingFor(
                Wait.forHttp("/api/overview")
                    .forPort(15672)
                    .withBasicCredentials(RABBITMQ_USER, RABBITMQ_PASSWORD)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)))) {
      rabbit.start();
      String auth =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (RABBITMQ_USER + ":" + RABBITMQ_PASSWORD).getBytes(StandardCharsets.UTF_8));
      HttpResponse<String> declared =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(10))
              .build()
              .send(
                  HttpRequest.newBuilder()
                      .uri(
                          URI.create(
                              "http://"
                                  + rabbit.getHost()
                                  + ":"
                                  + rabbit.getMappedPort(15672)
                                  + "/api/queues/%2F/bridge.ledger"))
                      .timeout(Duration.ofSeconds(10))
                      .header("Authorization", auth)
                      .header("Content-Type", "application/json")
                      .PUT(
                          HttpRequest.BodyPublishers.ofString(
                              "{\"durable\":true,\"arguments\":{\"x-queue-type\":\"quorum\"}}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(declared.statusCode()).isIn(201, 204);

      String url = "http://" + rabbit.getHost() + ":" + rabbit.getMappedPort(15692) + path + query;
      // Queue metrics are collected on an interval; wait until the declared queue is reported.
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      String body = get(url);
      while (!body.contains("queue=\"bridge.ledger\"") && System.nanoTime() < deadline) {
        Thread.sleep(1000);
        body = get(url);
      }
      assertThat(body).contains("queue=\"bridge.ledger\"");
      return metricNames(body);
    }
  }

  /** Names from both HELP/TYPE lines and samples of a Prometheus text exposition. */
  private static Set<String> metricNames(String body) {
    Set<String> names = new HashSet<>();
    for (String raw : body.split("\n")) {
      String line = raw.trim();
      if (line.startsWith("# HELP ") || line.startsWith("# TYPE ")) {
        names.add(line.split(" ")[2]);
      } else if (!line.isEmpty() && !line.startsWith("#")) {
        int brace = line.indexOf('{');
        int space = line.indexOf(' ');
        int end = brace != -1 ? brace : space;
        if (end > 0) {
          names.add(line.substring(0, end));
        }
      }
    }
    return names;
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> response =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("GET %s", url).isEqualTo(200);
    return response.body();
  }

  private static void collectDashboardExpressions(Path dashboard, List<String[]> out)
      throws Exception {
    JsonNode root;
    try (InputStream in = Files.newInputStream(dashboard)) {
      root = MAPPER.readTree(in);
    }
    for (JsonNode panel : root.get("panels")) {
      for (JsonNode target : panel.get("targets")) {
        out.add(new String[] {panel.get("title").asText(), target.get("expr").asText()});
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void collectRuleExpressions(Path rules, List<String[]> out) throws Exception {
    Map<String, Object> root;
    try (InputStream in = Files.newInputStream(rules)) {
      root = new Yaml().load(in);
    }
    for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
      for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
        out.add(new String[] {(String) rule.get("alert"), (String) rule.get("expr")});
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    return (Map<String, Object>) parent.get(key);
  }
}
