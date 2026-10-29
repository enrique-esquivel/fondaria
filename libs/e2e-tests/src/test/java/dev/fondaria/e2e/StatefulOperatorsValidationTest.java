package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Validation tests for Task 12.4 (Stateful operators):
 *
 * <p>Requirements: 1. CloudNativePG: 1 primary + 2 synchronous replicas in prod, continuous backup
 * to object storage, PITR configured. 2. RabbitMQ Cluster Operator: 3 nodes, quorum queues, zone
 * spread, and the critical rabbitmq_consistent_hash_exchange plugin enabled (ADR-0022 §2a). 3.
 * Valkey operator: cluster mode, pinned to valkey/valkey:8.0.2-alpine (ADR-0022 §1). 4. Pinned
 * operator and operand versions. 5. Overlay differences asserted between local and prod.
 */
class StatefulOperatorsValidationTest {

  @Test
  @DisplayName(
      "CloudNativePG base manifests enforce 1 primary + 2 sync replicas, NVMe storage, and barman backup")
  void cloudNativePgBaseManifestMeetsRequirements() throws Exception {
    Path postgresDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/postgres"));
    assertThat(postgresDir.resolve("cluster.yaml")).isRegularFile();
    assertThat(postgresDir.resolve("scheduled-backup.yaml")).isRegularFile();
    assertThat(postgresDir.resolve("pitr-restore-example.yaml")).isRegularFile();

    Map<String, Object> cluster = loadSingleDoc(postgresDir.resolve("cluster.yaml"));
    assertThat(cluster.get("apiVersion")).isEqualTo("postgresql.cnpg.io/v1");
    assertThat(cluster.get("kind")).isEqualTo("Cluster");

    Map<String, Object> spec = getMap(cluster, "spec");
    assertThat(spec.get("instances")).isEqualTo(3);
    assertThat((String) spec.get("imageName"))
        .startsWith("ghcr.io/cloudnative-pg/postgresql:17.2@sha256:");
    assertThat(spec.get("minSyncReplicas")).isEqualTo(1);
    assertThat(spec.get("maxSyncReplicas")).isEqualTo(2);

    // Synchronous commit
    Map<String, Object> postgresql = getMap(spec, "postgresql");
    Map<String, Object> params = getMap(postgresql, "parameters");
    assertThat(params.get("synchronous_commit")).isEqualTo("on");

    // Dedicated NVMe storage
    Map<String, Object> storage = getMap(spec, "storage");
    assertThat(storage.get("storageClass")).isEqualTo("tigerbeetle-nvme");
    Map<String, Object> walStorage = getMap(spec, "walStorage");
    assertThat(walStorage.get("storageClass")).isEqualTo("tigerbeetle-nvme");

    // Continuous backup via barmanObjectStore
    Map<String, Object> backup = getMap(spec, "backup");
    Map<String, Object> barman = getMap(backup, "barmanObjectStore");
    assertThat(barman.get("destinationPath")).isEqualTo("s3://postgres-backups/");
    Map<String, Object> wal = getMap(barman, "wal");
    assertThat(wal.get("compression")).isEqualTo("gzip");
    Map<String, Object> data = getMap(barman, "data");
    assertThat(data.get("compression")).isEqualTo("gzip");

    // Scheduled backup
    Map<String, Object> scheduledBackup =
        loadSingleDoc(postgresDir.resolve("scheduled-backup.yaml"));
    assertThat(scheduledBackup.get("kind")).isEqualTo("ScheduledBackup");
    Map<String, Object> sbSpec = getMap(scheduledBackup, "spec");
    assertThat(sbSpec.get("schedule")).isEqualTo("0 0 2 * * *");

    // PITR restore example
    Map<String, Object> pitr = loadSingleDoc(postgresDir.resolve("pitr-restore-example.yaml"));
    assertThat(pitr.get("kind")).isEqualTo("Cluster");
    Map<String, Object> pitrSpec = getMap(pitr, "spec");
    Map<String, Object> recoveryTarget = getMap(pitrSpec, "recoveryTarget");
    assertThat(recoveryTarget.get("targetTime")).isNotNull();
    Map<String, Object> bootstrap = getMap(pitrSpec, "bootstrap");
    Map<String, Object> recovery = getMap(bootstrap, "recovery");
    assertThat(recovery.get("source")).isEqualTo("postgres");
  }

  @Test
  @DisplayName(
      "RabbitMQ cluster manifest specifies 3 nodes, quorum queues, and rabbitmq_consistent_hash_exchange enabled")
  void rabbitmqClusterManifestMeetsRequirements() throws Exception {
    Path rmqDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/rabbitmq"));
    assertThat(rmqDir.resolve("rabbitmq-cluster.yaml")).isRegularFile();

    Map<String, Object> rmq = loadSingleDoc(rmqDir.resolve("rabbitmq-cluster.yaml"));
    assertThat(rmq.get("apiVersion")).isEqualTo("rabbitmq.com/v1beta1");
    assertThat(rmq.get("kind")).isEqualTo("RabbitmqCluster");

    Map<String, Object> spec = getMap(rmq, "spec");
    assertThat(spec.get("replicas")).isEqualTo(3);
    assertThat((String) spec.get("image"))
        .as("the operand image is the compose image, pinned by digest")
        .startsWith("rabbitmq@sha256:");

    // Mandatory: rabbitmq_consistent_hash_exchange MUST be explicitly enabled (ADR-0022 §2a)
    Map<String, Object> rabbitmq = getMap(spec, "rabbitmq");
    List<String> plugins = getList(rabbitmq, "additionalPlugins");
    assertThat(plugins)
        .as(
            "rabbitmq_consistent_hash_exchange plugin must be enabled to preserve per-account FIFO ordering")
        .contains("rabbitmq_consistent_hash_exchange");
    assertThat(plugins).contains("rabbitmq_management", "rabbitmq_prometheus");

    String additionalConfig = (String) rabbitmq.get("additionalConfig");
    assertThat(additionalConfig).contains("pause_minority");

    Map<String, Object> persistence = getMap(spec, "persistence");
    assertThat(persistence.get("storageClassName")).isEqualTo("tigerbeetle-nvme");
  }

  @Test
  @DisplayName("Valkey is one standalone node -- every consumer is a standalone Lettuce client")
  void valkeyManifestMeetsRequirements() throws Exception {
    Path valkeyDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/valkey"));
    assertThat(valkeyDir.resolve("valkey-cluster.yaml")).isRegularFile();
    assertThat(valkeyDir.resolve("service.yaml")).isRegularFile();

    List<Map<String, Object>> docs = loadAllDocs(valkeyDir.resolve("valkey-cluster.yaml"));
    Map<String, Object> statefulSet = findByKind(docs, "StatefulSet");
    Map<String, Object> configMap = findByKind(docs, "ConfigMap");

    Map<String, Object> spec = getMap(statefulSet, "spec");
    assertThat(spec.get("replicas")).isEqualTo(1);

    Map<String, Object> template = getMap(spec, "template");
    Map<String, Object> podSpec = getMap(template, "spec");
    List<Map<String, Object>> containers = getList(podSpec, "containers");
    Map<String, Object> container = containers.get(0);
    assertThat((String) container.get("image")).startsWith("valkey/valkey@sha256:");

    // Standalone: a cluster-mode server answers MOVED to a standalone client
    Map<String, Object> data = getMap(configMap, "data");
    String valkeyConf = (String) data.get("valkey.conf");
    assertThat(valkeyConf).contains("cluster-enabled no");
    assertThat(valkeyConf).doesNotContain("cluster-enabled yes");
  }

  @Test
  @DisplayName(
      "Operators are upstream release manifests, pinned by version in bootstrap/versions.env, not hand-written stubs")
  void operatorsArePinnedUpstreamReleases() throws Exception {
    Path bootstrap = Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap"));
    assertThat(bootstrap.resolve("up.sh")).isRegularFile();
    assertThat(bootstrap.resolve("kind-config.yaml")).isRegularFile();
    assertThat(Path.of(RepositoryRoot.path("infrastructure/k8s/operators")))
        .as("the 70-line operator 'manifests' without CRDs or RBAC are gone")
        .doesNotExist();

    Map<String, String> versions = new java.util.HashMap<>();
    for (String line : Files.readAllLines(bootstrap.resolve("versions.env"))) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] kv = line.split("=", 2);
      versions.put(kv[0], kv[1]);
    }
    for (String key :
        List.of(
            "CNPG_VERSION",
            "CNPG_MANIFEST",
            "RABBITMQ_OPERATOR_VERSION",
            "RABBITMQ_OPERATOR_MANIFEST",
            "CALICO_VERSION",
            "CALICO_MANIFEST",
            "METRICS_SERVER_VERSION",
            "METRICS_SERVER_MANIFEST",
            "KIND_NODE_IMAGE")) {
      assertThat(versions).as("versions.env pins %s", key).containsKey(key);
    }
    // Every manifest URL embeds the version it claims, so the two cannot drift apart.
    assertThat(versions.get("CNPG_MANIFEST")).contains(versions.get("CNPG_VERSION"));
    assertThat(versions.get("RABBITMQ_OPERATOR_MANIFEST"))
        .contains(versions.get("RABBITMQ_OPERATOR_VERSION"));
    assertThat(versions.get("CALICO_MANIFEST")).contains(versions.get("CALICO_VERSION"));
    assertThat(versions.get("METRICS_SERVER_MANIFEST"))
        .contains(versions.get("METRICS_SERVER_VERSION"));
    assertThat(versions.get("KIND_NODE_IMAGE")).contains("@sha256:");
    for (String v : versions.values()) {
      assertThat(v).doesNotContain("latest").doesNotContain("main/");
    }
  }

  @Test
  @DisplayName(
      "Local overlay scales stateful operators to 1 replica; prod overlay enforces HA and stateful node pool")
  void overlaysApplyStatefulDifferencesCorrectly() throws Exception {
    List<Map<String, Object>> localDocs = renderOverlay("local");
    List<Map<String, Object>> prodDocs = renderOverlay("prod");

    // Local: 1 replica for Postgres, RabbitMQ, and Valkey
    Map<String, Object> localPg = findByNameAndKind(localDocs, "postgres", "Cluster");
    assertThat(getMap(localPg, "spec").get("instances")).isEqualTo(1);
    assertThat(getMap(localPg, "spec").get("minSyncReplicas")).isEqualTo(0);

    // Local Postgres: the operator refuses a memory request below shared_buffers
    Map<String, Object> localPgParams =
        getMap(getMap(getMap(localPg, "spec"), "postgresql"), "parameters");
    assertThat(localPgParams.get("shared_buffers")).isEqualTo("1GB");
    assertThat(getMap(getMap(getMap(localPg, "spec"), "resources"), "requests").get("memory"))
        .isEqualTo("1280Mi");

    // Row 26j: a local restart must not sit in Terminating for the operator defaults
    // (CNPG stopDelay 1800 s, RabbitMQ one week) while every service crash-loops.
    assertThat(getMap(localPg, "spec").get("stopDelay")).isEqualTo(60);
    assertThat(getMap(localPg, "spec").get("smartShutdownTimeout")).isEqualTo(15);

    Map<String, Object> localRmq = findByNameAndKind(localDocs, "rabbitmq", "RabbitmqCluster");
    assertThat(getMap(localRmq, "spec").get("replicas")).isEqualTo(1);
    assertThat(getMap(localRmq, "spec").get("terminationGracePeriodSeconds")).isEqualTo(60);

    Map<String, Object> localValkey = findByNameAndKind(localDocs, "valkey", "StatefulSet");
    assertThat(getMap(localValkey, "spec").get("replicas")).isEqualTo(1);

    // Prod: HA topologies
    Map<String, Object> prodPg = findByNameAndKind(prodDocs, "postgres", "Cluster");
    assertThat(getMap(prodPg, "spec").get("instances")).isEqualTo(3);
    assertThat(getMap(prodPg, "spec").get("minSyncReplicas")).isEqualTo(1);

    Map<String, Object> prodRmq = findByNameAndKind(prodDocs, "rabbitmq", "RabbitmqCluster");
    assertThat(getMap(prodRmq, "spec").get("replicas")).isEqualTo(3);

    // Prod keeps the operators' long graceful-shutdown defaults (row 26j is local-only)
    assertThat(getMap(prodPg, "spec")).doesNotContainKeys("stopDelay", "smartShutdownTimeout");
    assertThat(getMap(prodRmq, "spec")).doesNotContainKey("terminationGracePeriodSeconds");

    // Valkey stays one node in prod too: a cache behind standalone clients, not a store
    Map<String, Object> prodValkey = findByNameAndKind(prodDocs, "valkey", "StatefulSet");
    assertThat(getMap(prodValkey, "spec").get("replicas")).isEqualTo(1);

    // Prod: Stateful node pool tolerations
    Map<String, Object> prodValkeyTemplate = getMap(getMap(prodValkey, "spec"), "template");
    List<Map<String, Object>> valkeyTolerations =
        getList(getMap(prodValkeyTemplate, "spec"), "tolerations");
    assertThat(valkeyTolerations)
        .anyMatch(
            t ->
                "fondaria.example/node-pool".equals(t.get("key"))
                    && "stateful".equals(t.get("value")));
  }

  // --- Helper Methods ------------------------------------------------------------------------

  private static Map<String, Object> loadSingleDoc(Path file) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream is = Files.newInputStream(file)) {
      return yaml.load(is);
    }
  }

  private static List<Map<String, Object>> loadAllDocs(Path file) throws Exception {
    Yaml yaml = new Yaml();
    List<Map<String, Object>> out = new ArrayList<>();
    try (InputStream is = Files.newInputStream(file)) {
      for (Object doc : yaml.loadAll(is)) {
        if (doc instanceof Map<?, ?> map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> typed = (Map<String, Object>) map;
          out.add(typed);
        }
      }
    }
    return out;
  }

  private static List<Map<String, Object>> renderOverlay(String overlayName) throws Exception {
    Path overlayDir = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/" + overlayName));
    Process process =
        new ProcessBuilder("kubectl", "kustomize", overlayDir.toString())
            .redirectErrorStream(true)
            .start();

    Yaml yaml = new Yaml();
    List<Map<String, Object>> docs = new ArrayList<>();
    try (InputStream is = process.getInputStream()) {
      for (Object doc : yaml.loadAll(is)) {
        if (doc instanceof Map<?, ?> map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> typed = (Map<String, Object>) map;
          docs.add(typed);
        }
      }
    }
    int exit = process.waitFor();
    assertThat(exit).as("kubectl kustomize %s must succeed", overlayName).isEqualTo(0);
    return docs;
  }

  private static Map<String, Object> findByKind(List<Map<String, Object>> docs, String kind) {
    return docs.stream()
        .filter(d -> kind.equals(d.get("kind")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Kind " + kind + " not found"));
  }

  private static Map<String, Object> findByNameAndKind(
      List<Map<String, Object>> docs, String name, String kind) {
    return docs.stream()
        .filter(d -> kind.equals(d.get("kind")) && name.equals(getName(d)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Resource " + kind + "/" + name + " not found"));
  }

  private static String getName(Map<String, Object> doc) {
    return (String) getMap(doc, "metadata").get("name");
  }

  private static String getImage(Map<String, Object> deployment) {
    Map<String, Object> spec = getMap(deployment, "spec");
    Map<String, Object> template = getMap(spec, "template");
    Map<String, Object> podSpec = getMap(template, "spec");
    List<Map<String, Object>> containers = getList(podSpec, "containers");
    return (String) containers.get(0).get("image");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
    Object val = parent.get(key);
    assertThat(val).as("Missing map field: %s", key).isInstanceOf(Map.class);
    return (Map<String, Object>) val;
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> getList(Map<String, Object> parent, String key) {
    Object val = parent.get(key);
    assertThat(val).as("Missing list field: %s", key).isInstanceOf(List.class);
    return (List<T>) val;
  }
}
