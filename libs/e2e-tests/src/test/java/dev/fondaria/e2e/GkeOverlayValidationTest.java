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
  @DisplayName(
      "The GKE overlay sizes Postgres and RabbitMQ for the demo load; the small profile does not")
  void datastoresSizedForTheDemoLoad() throws Exception {
    Map<String, Object> postgres = map(find(renderOverlay("gke"), "Cluster", "postgres"), "spec");
    assertThat(map(postgres, "resources"))
        .as("4 guaranteed CPUs and 8 GiB per Postgres instance")
        .isEqualTo(
            Map.of(
                "requests", Map.of("cpu", "4", "memory", "8Gi"),
                "limits", Map.of("cpu", "4", "memory", "8Gi")));
    assertThat(map(map(postgres, "postgresql"), "parameters").get("shared_buffers"))
        .isEqualTo("2GB");
    Map<String, Object> rabbit =
        map(find(renderOverlay("gke"), "RabbitmqCluster", "rabbitmq"), "spec");
    assertThat(map(rabbit, "resources"))
        .isEqualTo(
            Map.of(
                "requests", Map.of("cpu", "2", "memory", "4Gi"),
                "limits", Map.of("cpu", "6", "memory", "4Gi")));

    List<Map<String, Object>> small = renderOverlay("gke-small");
    assertThat(map(map(map(find(small, "Cluster", "postgres"), "spec"), "resources"), "limits"))
        .as("4-vCPU shared nodes keep the base's Postgres limits")
        .isEqualTo(Map.of("cpu", "2", "memory", "4Gi"));
    assertThat(
            map(
                map(map(find(small, "RabbitmqCluster", "rabbitmq"), "spec"), "resources"),
                "limits"))
        .isEqualTo(Map.of("cpu", "1", "memory", "2Gi"));
  }

  @Test
  @DisplayName(
      "The big stateful pool is created only after the images are pushed, under a short switch")
  void statefulPoolBillsOnlyWhileDeployed() throws Exception {
    String up = Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/up.sh")));
    assertThat(up).as("gke-up builds only the stateless pool").doesNotContain("node-pools create");
    assertThat(up).contains("arm_deadman \"${GKE_TTL_MINUTES}\"");

    int registry = up.indexOf("artifacts repositories create");
    int background = up.indexOf("\"${here}/build.sh\" > \"${build_log}\" 2>&1 &");
    int cluster = up.indexOf("container clusters create");
    assertThat(background)
        .as("gke-up builds the images in the background once the registry exists")
        .isGreaterThan(registry)
        .isLessThan(cluster);
    assertThat(up.indexOf("wait \"${build_pid}\""))
        .as("and waits for that build before it reports ready")
        .isGreaterThan(cluster)
        .isLessThan(up.indexOf("log \"ready."));

    String buildScript =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/build.sh")));
    assertThat(buildScript)
        .contains("skaffold build")
        .contains("--platform=linux/amd64")
        .as("a build is reused only for the exact working tree it was built from")
        .contains("git write-tree");
    assertThat(buildScript.indexOf("rm -f \"${stamp_file}\""))
        .as("a build that dies half-way is never reused")
        .isNotNegative()
        .isLessThan(buildScript.indexOf("skaffold build"));

    String deploy = Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/deploy.sh")));
    int build = deploy.indexOf("\"${here}/build.sh\"");
    int pool = deploy.indexOf("stateful-pool.sh\"");
    int apply = deploy.indexOf("skaffold deploy");
    assertThat(build).as("images are built and pushed, or reused if unchanged").isNotNegative();
    assertThat(pool).as("before the stateful pool exists").isGreaterThan(build);
    assertThat(apply).as("which exists before the platform is deployed").isGreaterThan(pool);

    String statefulPool =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/stateful-pool.sh")));
    assertThat(statefulPool.indexOf("arm_deadman \"${GKE_STATEFUL_TTL_MINUTES}\""))
        .as("the switch is re-armed before the pool is created")
        .isNotNegative()
        .isLessThan(statefulPool.indexOf("node-pools create stateful"));
    assertThat(statefulPool)
        .contains("--machine-type=\"${GKE_STATEFUL_MACHINE}\"")
        .contains("COST WINDOW");

    String config = Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/config.env")));
    assertThat(config)
        .contains("GKE_TTL_MINUTES=\"${GKE_TTL_MINUTES:-120}\"")
        .contains("GKE_STATEFUL_TTL_MINUTES=\"${GKE_STATEFUL_TTL_MINUTES:-90}\"")
        .contains("GKE_STATEFUL_VCPUS=\"${GKE_STATEFUL_VCPUS:-8}\"")
        .contains("GKE_STATELESS_VCPUS=\"${GKE_STATELESS_VCPUS:-4}\"");
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
  @DisplayName(
      "Unmeshed callers are admitted from up.sh's pod range, and DNS reaches node-local-dns")
  void unmeshedCallersUseGkePodRangeAndDnsReachesNodeLocalCache() throws Exception {
    String config = Files.readString(Path.of(RepositoryRoot.path("infrastructure/gke/config.env")));
    Matcher matcher =
        Pattern.compile("GKE_PODS_RANGE=\"\\$\\{GKE_PODS_RANGE:-([0-9./]+)}\"").matcher(config);
    assertThat(matcher.find()).as("GKE_PODS_RANGE default in config.env").isTrue();

    for (String overlay : List.of("gke", "gke-small")) {
      List<Map<String, Object>> docs = renderOverlay(overlay);
      Map<String, Object> callers = find(docs, "NetworkAuthentication", "unmeshed-cluster-callers");
      assertThat(list(map(callers, "spec"), "networks"))
          .as("%s: unmeshed-cluster-callers networks", overlay)
          .extracting(n -> n.get("cidr"))
          .containsExactly(matcher.group(1));
      assertThat(find(docs, "NetworkPolicy", "allow-node-local-dns"))
          .as("%s: DNS egress to GKE's NodeLocal DNSCache", overlay)
          .isNotNull();
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

  @Test
  @DisplayName("The small GKE profile keeps three copies of every datastore on three machines")
  void smallProfileKeepsThreeCopiesOfEveryDatastore() throws Exception {
    List<Map<String, Object>> docs = renderOverlay("gke-small");

    Map<String, Object> tigerbeetle = find(docs, "StatefulSet", "tigerbeetle");
    assertThat(map(tigerbeetle, "spec").get("replicas")).isEqualTo(3);
    assertThat(tigerbeetle.toString())
        .as("still one TigerBeetle replica per node")
        .contains("requiredDuringSchedulingIgnoredDuringExecution")
        .contains("kubernetes.io/hostname");
    assertThat(map(find(docs, "ConfigMap", "tigerbeetle-config"), "data"))
        .containsEntry("TIGERBEETLE_REPLICA_COUNT", "3");

    // Every client must name exactly the three replicas the cluster formats with: a client
    // listing a fourth address would wait on a replica that never exists.
    String threeReplicas = "10.96.100.100:3000,10.96.100.101:3000,10.96.100.102:3000";
    for (String configMap :
        List.of(
            "tigerbeetle-config",
            "contribution-api-config",
            "allotment-job-config",
            "balance-api-config",
            "eod-job-config",
            "valuation-api-config")) {
      assertThat(map(find(docs, "ConfigMap", configMap), "data").get("TIGERBEETLE_ADDRESSES"))
          .as("TIGERBEETLE_ADDRESSES in %s", configMap)
          .isEqualTo(threeReplicas);
    }
    assertThat(
            map(find(docs, "ConfigMap", "tigerbeetle-cdc-config"), "data")
                .get("CDC_TIGERBEETLE_ADDRESSES"))
        .isEqualTo(threeReplicas);

    assertThat(map(find(docs, "Cluster", "postgres"), "spec").get("instances")).isEqualTo(3);
    assertThat(map(find(docs, "RabbitmqCluster", "rabbitmq"), "spec").get("replicas")).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Every overlay spaces checkpoints out (load run 6) on a WAL volume that holds max_wal_size")
  void everyOverlayCarriesTheWalSettings() throws Exception {
    for (String overlay : List.of("local", "prod", "gke", "gke-small")) {
      Map<String, Object> postgres =
          map(find(renderOverlay(overlay), "Cluster", "postgres"), "spec");
      assertThat(map(map(postgres, "postgresql"), "parameters"))
          .as("WAL parameters in overlay %s", overlay)
          .containsEntry("max_wal_size", "8GB")
          .containsEntry("checkpoint_timeout", "15min")
          .containsEntry("wal_compression", "lz4");
      // max_wal_size is a soft limit: WAL can overshoot it between checkpoints.
      assertThat(gib(map(postgres, "walStorage").get("size")))
          .as("walStorage in overlay %s must hold 1.5x max_wal_size", overlay)
          .isGreaterThanOrEqualTo(8 * 1.5);
    }
  }

  @Test
  @DisplayName("The small GKE profile fits a new billing account's 250 GB SSD quota")
  void smallProfileFitsDefaultSsdQuota() throws Exception {
    List<Map<String, Object>> docs = renderOverlay("gke-small");

    // The ledger, the database and the broker stay on SSD.
    assertThat(volumeClass(find(docs, "StatefulSet", "tigerbeetle"))).isEqualTo("tigerbeetle-nvme");
    // Files, metrics, traces and logs move to GKE's built-in pd-standard class.
    for (String statefulSet : List.of("object-store", "prometheus", "loki", "tempo")) {
      assertThat(volumeClass(find(docs, "StatefulSet", statefulSet)))
          .as("storage class of %s", statefulSet)
          .isEqualTo("standard");
    }

    double ssdGib = 0;
    for (Map<String, Object> statefulSet : ofKind(docs, "StatefulSet")) {
      Map<String, Object> spec = map(statefulSet, "spec");
      for (Map<String, Object> claim : list(spec, "volumeClaimTemplates")) {
        Map<String, Object> claimSpec = map(claim, "spec");
        if ("tigerbeetle-nvme".equals(claimSpec.get("storageClassName"))) {
          ssdGib +=
              gib(map(map(claimSpec, "resources"), "requests").get("storage"))
                  * (Integer) spec.getOrDefault("replicas", 1);
        }
      }
    }
    Map<String, Object> postgres = map(find(docs, "Cluster", "postgres"), "spec");
    ssdGib +=
        (gib(map(postgres, "storage").get("size")) + gib(map(postgres, "walStorage").get("size")))
            * (Integer) postgres.get("instances");
    Map<String, Object> rabbit = map(find(docs, "RabbitmqCluster", "rabbitmq"), "spec");
    ssdGib += gib(map(rabbit, "persistence").get("storage")) * (Integer) rabbit.get("replicas");

    // 250 GB is the quota; disks count in GB (1 GiB = 1.074 GB), boot disks are pd-standard.
    assertThat(ssdGib * 1.074).as("SSD claimed, in GB").isLessThanOrEqualTo(250);
  }

  @Test
  @DisplayName("Skaffold's gke-small profile deploys the small overlay")
  void skaffoldSmallProfileTargetsSmallOverlay() throws Exception {
    Map<String, Object> skaffold;
    try (InputStream in = Files.newInputStream(Path.of(RepositoryRoot.path("skaffold.yaml")))) {
      skaffold = new Yaml().load(in);
    }
    Map<String, Object> small =
        list(skaffold, "profiles").stream()
            .filter(p -> "gke-small".equals(p.get("name")))
            .findFirst()
            .orElseThrow();
    assertThat(list(small, "patches"))
        .anySatisfy(p -> assertThat(p.get("path")).isEqualTo("/build/local/push"))
        .anySatisfy(
            p -> assertThat(p.get("value")).isEqualTo("infrastructure/k8s/overlays/gke-small"));
  }

  private static String volumeClass(Map<String, Object> statefulSet) {
    return (String)
        map(list(map(statefulSet, "spec"), "volumeClaimTemplates").get(0), "spec")
            .get("storageClassName");
  }

  private static double gib(Object quantity) {
    String value = String.valueOf(quantity);
    if (value.endsWith("Gi")) {
      return Double.parseDouble(value.substring(0, value.length() - 2));
    }
    if (value.endsWith("Mi")) {
      return Double.parseDouble(value.substring(0, value.length() - 2)) / 1024;
    }
    throw new IllegalArgumentException("unexpected storage quantity " + value);
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
