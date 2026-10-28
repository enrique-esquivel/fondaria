package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Per-container CPU, throttling and memory come from each kubelet's cAdvisor: the scrape job, the
 * read-only RBAC it needs and the egress to the kubelet port must all be present, or the series a
 * load run is sized from are silently missing.
 */
class PrometheusCadvisorScrapeTest {

  private static final String PROMETHEUS = "infrastructure/k8s/base/observability/prometheus/";

  @Test
  @DisplayName(
      "cadvisor.yml scrapes every node's kubelet on /metrics/cadvisor and keeps CPU and memory")
  @SuppressWarnings("unchecked")
  void scrapesKubeletCadvisor() {
    Map<String, Object> scrapeK8s =
        find(PROMETHEUS + "configmap.yaml", "ConfigMap", "prometheus-scrape-k8s");
    String file = (String) ((Map<String, Object>) scrapeK8s.get("data")).get("cadvisor.yml");
    assertThat(file).as("cadvisor.yml in prometheus-scrape-k8s").isNotNull();

    Map<String, Object> job =
        ((List<Map<String, Object>>)
                new Yaml().<Map<String, Object>>load(file).get("scrape_configs"))
            .getFirst();
    assertThat(job)
        .containsEntry("scheme", "https")
        .containsEntry("metrics_path", "/metrics/cadvisor");
    assertThat(job.get("kubernetes_sd_configs").toString()).contains("role=node");
    assertThat(job.get("metric_relabel_configs").toString())
        .contains("container_cpu_usage_seconds_total")
        .contains("container_cpu_cfs_throttled_periods_total")
        .contains("container_memory_working_set_bytes");
  }

  @Test
  @DisplayName(
      "Prometheus may read kubelet metrics, but not the kubelet's whole API through nodes/proxy")
  @SuppressWarnings("unchecked")
  void rbacGrantsNodesMetricsOnly() {
    Map<String, Object> role =
        find(PROMETHEUS + "serviceaccount.yaml", "ClusterRole", "prometheus-pod-discovery");
    List<String> resources = new ArrayList<>();
    for (Map<String, Object> rule : (List<Map<String, Object>>) role.get("rules")) {
      resources.addAll((List<String>) rule.get("resources"));
      if (((List<String>) rule.get("resources")).contains("nodes/metrics")) {
        assertThat((List<String>) rule.get("verbs")).containsExactly("get");
      }
    }
    assertThat(resources).contains("nodes", "nodes/metrics").doesNotContain("nodes/proxy");
  }

  @Test
  @DisplayName("prometheus-policy allows egress to the kubelet port 10250")
  void networkPolicyAllowsKubeletPort() {
    Map<String, Object> policy =
        find(
            "infrastructure/k8s/base/observability/networkpolicies/observability-network-policies.yaml",
            "NetworkPolicy",
            "prometheus-policy");
    assertThat(((Map<?, ?>) policy.get("spec")).get("egress").toString()).contains("port=10250");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> find(String relativePath, String kind, String name) {
    Path path = Path.of(RepositoryRoot.path(relativePath));
    try (InputStream in = Files.newInputStream(path)) {
      for (Object doc : new Yaml().loadAll(in)) {
        Map<String, Object> map = (Map<String, Object>) doc;
        if (map != null
            && kind.equals(map.get("kind"))
            && name.equals(((Map<String, Object>) map.get("metadata")).get("name"))) {
          return map;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new AssertionError("no " + kind + " " + name + " in " + path);
  }
}
