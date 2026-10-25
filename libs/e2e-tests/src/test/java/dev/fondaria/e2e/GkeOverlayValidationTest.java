package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The throwaway GKE load-test environment (ADR-0036): {@code infrastructure/k8s/overlays/gke} keeps
 * the prod topology, exposes nothing publicly, carries no credential from the public repository,
 * and agrees with the network ranges {@code infrastructure/gke/up.sh} creates the cluster with.
 */
class GkeOverlayValidationTest {

  @Test
  @DisplayName("The GKE overlay keeps the prod topology")
  void keepsProdTopology() throws Exception {
    List<Map<String, Object>> docs = renderOverlay("gke");

    Map<String, Object> tigerbeetle = find(docs, "StatefulSet", "tigerbeetle");
    assertThat(map(tigerbeetle, "spec").get("replicas")).isEqualTo(6);
    assertThat(tigerbeetle.toString())
        .as("one TigerBeetle replica per node, as in prod")
        .contains("requiredDuringSchedulingIgnoredDuringExecution")
        .contains("kubernetes.io/hostname");

    assertThat(map(find(docs, "Cluster", "postgres"), "spec").get("instances")).isEqualTo(3);
    assertThat(map(find(docs, "RabbitmqCluster", "rabbitmq"), "spec").get("replicas")).isEqualTo(3);
    assertThat(map(find(docs, "Deployment", "contribution-api"), "spec").get("replicas"))
        .isEqualTo(3);
  }

  @Test
  @DisplayName("Nothing in the GKE overlay is exposed outside the cluster")
  void exposesNothingPublicly() throws Exception {
    List<Map<String, Object>> docs = renderOverlay("gke");

    for (Map<String, Object> service : ofKind(docs, "Service")) {
      assertThat(map(service, "spec").get("type"))
          .as("Service %s must not be a LoadBalancer or NodePort", name(service))
          .isNotIn("LoadBalancer", "NodePort");
    }
    Map<String, Object> proxy = find(docs, "EnvoyProxy", "fondaria-gateway-proxy-config");
    Map<String, Object> kubernetes = map(map(map(proxy, "spec"), "provider"), "kubernetes");
    assertThat(map(kubernetes, "envoyService").get("type"))
        .as("the generated gateway Service must not become a cloud load balancer")
        .isEqualTo("ClusterIP");
  }

  @Test
  @DisplayName("GKE storage is SSD persistent disk that dies with the project")
  void storageIsDeletablePdSsd() throws Exception {
    Map<String, Object> storageClass =
        find(renderOverlay("gke"), "StorageClass", "tigerbeetle-nvme");
    assertThat(storageClass.get("provisioner")).isEqualTo("pd.csi.storage.gke.io");
    assertThat(storageClass.get("reclaimPolicy")).isEqualTo("Delete");
    assertThat(map(storageClass, "parameters").get("type")).isEqualTo("pd-ssd");
  }

  @Test
  @DisplayName("GKE credentials are generated, not the local overlay's published demo values")
  void credentialsAreGeneratedNotDemoLiterals() throws Exception {
    List<Map<String, Object>> docs = renderOverlay("gke");

    assertThat(ofKind(docs, "ExternalSecret")).isEmpty();
    assertThat(ofKind(docs, "ClusterIssuer")).isEmpty();
    assertThat(ofKind(docs, "Certificate")).isEmpty();
    for (String secret :
        List.of(
            "object-store-credentials",
            "grafana-admin-secret",
            "balance-api-secret",
            "contribution-api-secret",
            "valkey-credentials",
            "keycloak-admin-secret",
            "keycloak-client-secrets",
            "fondaria-gateway-tls")) {
      assertThat(find(docs, "Secret", secret)).as("generated Secret %s", secret).isNotNull();
    }

    // The local overlay's literals (overlays/local/kustomization.yaml), base64-encoded as they
    // would appear in a rendered Secret's data.
    String rendered = docs.toString();
    for (String demoValue :
        List.of(
            "admin_dev_pass",
            "funds_dev_pass",
            "internal-services-secret",
            "employer-portal-secret",
            "local_dev_cursor_signing_key_not_for_production")) {
      String encoded =
          java.util.Base64.getEncoder().encodeToString(demoValue.getBytes(StandardCharsets.UTF_8));
      assertThat(rendered).as("demo credential %s", demoValue).doesNotContain(encoded);
    }
    assertThat(find(docs, "ConfigMap", "keycloak-demo-users"))
        .as("no demo realm users on a cloud cluster")
        .isNull();
  }

  @Test
  @DisplayName("up.sh's services range contains TigerBeetle's fixed ClusterIPs")
  void servicesRangeContainsTigerBeetleClusterIps() throws Exception {
    String config = Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/config.env")));
    Matcher matcher =
        Pattern.compile("GKE_SERVICES_RANGE=\"\\$\\{GKE_SERVICES_RANGE:-([0-9./]+)}\"")
            .matcher(config);
    assertThat(matcher.find()).as("GKE_SERVICES_RANGE default in config.env").isTrue();
    String[] cidr = matcher.group(1).split("/");
    long network = ipv4(cidr[0]);
    long mask = 0xFFFFFFFFL << (32 - Integer.parseInt(cidr[1])) & 0xFFFFFFFFL;

    List<String> fixed = new ArrayList<>();
    for (Map<String, Object> service : ofKind(renderOverlay("gke"), "Service")) {
      Object clusterIp = map(service, "spec").get("clusterIP");
      if (clusterIp instanceof String ip && !"None".equals(ip)) {
        fixed.add(ip);
      }
    }
    assertThat(fixed).as("TigerBeetle's six fixed ClusterIPs").hasSizeGreaterThanOrEqualTo(6);
    for (String ip : fixed) {
      assertThat(ipv4(ip) & mask)
          .as("fixed ClusterIP %s inside services range %s", ip, matcher.group(1))
          .isEqualTo(network & mask);
    }
  }

  @Test
  @DisplayName("Skaffold's gke profile pushes images and deploys the GKE overlay")
  void skaffoldProfileTargetsGkeOverlay() throws Exception {
    Map<String, Object> skaffold;
    try (InputStream in = Files.newInputStream(Path.of(RepositoryRoot.path("skaffold.yaml")))) {
      skaffold = new Yaml().load(in);
    }
    List<Map<String, Object>> profiles = list(skaffold, "profiles");
    Map<String, Object> gke =
        profiles.stream().filter(p -> "gke".equals(p.get("name"))).findFirst().orElseThrow();
    List<Map<String, Object>> patches = list(gke, "patches");
    assertThat(patches)
        .anySatisfy(
            p -> {
              assertThat(p.get("path")).isEqualTo("/build/local/push");
              assertThat(p.get("value")).isEqualTo(true);
            })
        .anySatisfy(
            p -> {
              assertThat(p.get("path")).isEqualTo("/manifests/kustomize/paths/0");
              assertThat(p.get("value")).isEqualTo("infrastructure/k8s/overlays/gke");
            });
  }

  private static long ipv4(String address) throws Exception {
    long value = 0;
    for (byte b : InetAddress.getByName(address).getAddress()) {
      value = value << 8 | (b & 0xFF);
    }
    return value;
  }

  private static List<Map<String, Object>> renderOverlay(String overlay) throws Exception {
    String kubectl = findExecutable("kubectl");
    assumeTrue(kubectl != null, "kubectl is required to render the overlay");
    Path overlayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/" + overlay));
    Process process =
        new ProcessBuilder(kubectl, "kustomize", overlayPath.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue())
        .as("kubectl kustomize %s must succeed (run make local-secrets first)", overlay)
        .isZero();

    List<Map<String, Object>> docs = new ArrayList<>();
    for (Object doc : new Yaml().loadAll(out)) {
      if (doc instanceof Map<?, ?> m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        docs.add(typed);
      }
    }
    return docs;
  }

  private static List<Map<String, Object>> ofKind(List<Map<String, Object>> docs, String kind) {
    return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
  }

  private static Map<String, Object> find(
      List<Map<String, Object>> docs, String kind, String name) {
    return ofKind(docs, kind).stream().filter(d -> name.equals(name(d))).findFirst().orElse(null);
  }

  private static String name(Map<String, Object> doc) {
    return (String) map(doc, "metadata").get("name");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    return value instanceof List ? (List<Map<String, Object>>) value : List.of();
  }

  private static String findExecutable(String name) {
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        File file = new File(dir, name);
        if (file.canExecute()) {
          return file.getAbsolutePath();
        }
      }
    }
    return null;
  }
}
