package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
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
 * Acceptance criteria tests for Task 12.3 (TigerBeetle StatefulSet): 1. 6 replicas in prod, 1
 * locally. 2. Dedicated StorageClass (tigerbeetle-nvme) on NVMe, direct disk I/O, not network
 * storage. 3. Stable network identity: address list generated from ordinal matches replica indices
 * exactly. 4. Format-once init container prevents reformatting on existing volume. 5.
 * PodDisruptionBudget allowing at most one unavailable replica (maxUnavailable: 1). 6. Pod
 * anti-affinity forcing distinct nodes, and zone spread. 7. Security context: non-root, read-only
 * root filesystem, dropped capabilities, RuntimeDefault.
 */
class TigerBeetleStatefulSetValidationTest {

  @Test
  @DisplayName(
      "Base StatefulSet specifies 6 replicas, ordered ready, and dedicated NVMe StorageClass")
  void baseStatefulSetMatchesRequirements() throws Exception {
    Path baseTbDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle"));
    assertThat(baseTbDir.resolve("statefulset.yaml")).isRegularFile();
    assertThat(baseTbDir.resolve("service.yaml")).isRegularFile();
    assertThat(baseTbDir.resolve("configmap.yaml")).isRegularFile();
    assertThat(baseTbDir.resolve("pdb.yaml")).isRegularFile();
    assertThat(baseTbDir.resolve("serviceaccount.yaml")).isRegularFile();

    Yaml yaml = new Yaml();
    Map<String, Object> ssDoc;
    try (InputStream in = new FileInputStream(baseTbDir.resolve("statefulset.yaml").toFile())) {
      ssDoc = yaml.load(in);
    }

    assertThat(ssDoc.get("kind")).isEqualTo("StatefulSet");
    Map<String, Object> spec = getMap(ssDoc, "spec");
    assertThat(spec.get("serviceName")).isEqualTo("tigerbeetle");
    assertThat(spec.get("replicas")).isEqualTo(6);
    assertThat(spec.get("podManagementPolicy")).isEqualTo("OrderedReady");

    // VolumeClaimTemplate dedicated storageClass tigerbeetle-nvme
    List<Map<String, Object>> vcts = getList(spec, "volumeClaimTemplates");
    assertThat(vcts).hasSize(1);
    Map<String, Object> vct = vcts.get(0);
    assertThat(getName(vct)).isEqualTo("data");
    Map<String, Object> vctSpec = getMap(vct, "spec");
    assertThat(vctSpec.get("storageClassName"))
        .as("Must use dedicated NVMe StorageClass for direct I/O")
        .isEqualTo("tigerbeetle-nvme");

    // Pod template securityContext
    Map<String, Object> template = getMap(spec, "template");
    Map<String, Object> podSpec = getMap(template, "spec");
    Map<String, Object> podSec = getMap(podSpec, "securityContext");
    assertThat(podSec.get("runAsNonRoot")).isEqualTo(true);
    assertThat(podSec.get("runAsUser")).isEqualTo(65532);
    // io_uring is TigerBeetle's I/O engine; the runtime default profile blocks it (verified:
    // "io_uring is not available ... PermissionDenied" under RuntimeDefault on kind).
    assertThat(getMap(podSec, "seccompProfile").get("type")).isEqualTo("Unconfined");

    // Init container format-once logic
    List<Map<String, Object>> initContainers = getList(podSpec, "initContainers");
    assertThat(initContainers).hasSize(1);
    Map<String, Object> init = initContainers.get(0);
    assertThat(init.get("name")).isEqualTo("tigerbeetle-init");
    assertThat(getMap(init, "securityContext").get("readOnlyRootFilesystem")).isEqualTo(true);
    assertThat(getMap(init, "securityContext").get("allowPrivilegeEscalation")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<String> drop =
        (List<String>) getMap(getMap(init, "securityContext"), "capabilities").get("drop");
    assertThat(drop).contains("ALL");

    // Main container
    List<Map<String, Object>> containers = getList(podSpec, "containers");
    assertThat(containers).hasSize(1);
    Map<String, Object> tb = containers.get(0);
    assertThat(tb.get("name")).isEqualTo("tigerbeetle");
    assertThat(getMap(tb, "securityContext").get("readOnlyRootFilesystem")).isEqualTo(true);
    assertThat(getMap(tb, "securityContext").get("allowPrivilegeEscalation")).isEqualTo(false);

    // Probes
    assertThat(tb.get("startupProbe")).as("Startup probe must be configured").isNotNull();
    assertThat(tb.get("livenessProbe")).as("Liveness probe must be configured").isNotNull();
    assertThat(tb.get("readinessProbe")).as("Readiness probe must be configured").isNotNull();

    // Zone spread and anti-affinity
    List<Map<String, Object>> tsc = getList(podSpec, "topologySpreadConstraints");
    assertThat(tsc).isNotEmpty();
    assertThat(tsc.get(0).get("topologyKey")).isEqualTo("topology.kubernetes.io/zone");

    Map<String, Object> affinity = getMap(podSpec, "affinity");
    assertThat(affinity).as("Pod anti-affinity must be configured to separate nodes").isNotNull();
  }

  @Test
  @DisplayName(
      "Replica-to-address mapping is one fixed-ClusterIP Service per replica index, IP literals only")
  void replicaToAddressMappingMatchesReplicaIndicesExactly() throws Exception {
    Path baseTbDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle"));
    Yaml yaml = new Yaml();

    List<Map<String, Object>> docs = new ArrayList<>();
    try (InputStream in = new FileInputStream(baseTbDir.resolve("configmap.yaml").toFile())) {
      for (Object obj : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) obj;
        docs.add(doc);
      }
    }
    List<Map<String, Object>> services = new ArrayList<>();
    try (InputStream in = new FileInputStream(baseTbDir.resolve("service.yaml").toFile())) {
      for (Object obj : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) obj;
        services.add(doc);
      }
    }

    Map<String, Object> cmDoc =
        docs.stream()
            .filter(d -> "tigerbeetle-config".equals(getName(d)))
            .findFirst()
            .orElseThrow();

    Map<String, Object> data = getMap(cmDoc, "data");
    String addresses = (String) data.get("TIGERBEETLE_ADDRESSES");
    assertThat(addresses).isNotBlank();

    String[] parts = addresses.split(",");
    int replicaCount = Integer.parseInt((String) data.get("TIGERBEETLE_REPLICA_COUNT"));
    assertThat(parts).hasSize(replicaCount);

    // TigerBeetle 0.17 (replica and client alike) parses --addresses as IP literals and does
    // not resolve hostnames. Each entry is the fixed ClusterIP of the Service selecting that
    // one replica pod -- stable across pod restarts, unlike a pod IP.
    for (int i = 0; i < replicaCount; i++) {
      final int ordinal = i;
      Map<String, Object> service =
          services.stream()
              .filter(d -> ("tigerbeetle-" + ordinal).equals(getName(d)))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no Service tigerbeetle-" + ordinal));
      Map<String, Object> spec = getMap(service, "spec");
      String clusterIp = (String) spec.get("clusterIP");
      assertThat(clusterIp).matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
      assertThat(parts[i])
          .as("Ordinal index %d must be the fixed ClusterIP of Service tigerbeetle-%d", i, i)
          .isEqualTo(clusterIp + ":3000");
      Map<String, Object> selector = getMap(spec, "selector");
      assertThat(selector.get("statefulset.kubernetes.io/pod-name")).isEqualTo("tigerbeetle-" + i);
    }

    // Every client carries the identical list: a client given a subset (e.g. replica 0 only)
    // makes that replica a single point of failure for the whole platform.
    Path base = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    for (String client :
        List.of("contribution-api", "balance-api", "valuation-api", "allotment-job", "eod-job")) {
      Map<String, Object> clientCm;
      try (InputStream in =
          new FileInputStream(base.resolve(client + "/configmap.yaml").toFile())) {
        clientCm = yaml.load(in);
      }
      assertThat(getMap(clientCm, "data").get("TIGERBEETLE_ADDRESSES"))
          .as("%s must be given every replica's address", client)
          .isEqualTo(addresses);
    }
    Map<String, Object> cdcCm = null;
    try (InputStream in =
        new FileInputStream(base.resolve("tigerbeetle-cdc/configmap.yaml").toFile())) {
      for (Object obj : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) obj;
        if ("tigerbeetle-cdc-config".equals(getName(doc))) {
          cdcCm = doc;
        }
      }
    }
    assertThat(cdcCm).isNotNull();
    assertThat(getMap(cdcCm, "data").get("CDC_TIGERBEETLE_ADDRESSES")).isEqualTo(addresses);
  }

  @Test
  @DisplayName("Init script enforces format-once invariant and refuses to reformat existing volume")
  void initScriptEnforcesFormatOnceInvariant() throws Exception {
    Path baseTbDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle"));
    Yaml yaml = new Yaml();

    List<Map<String, Object>> docs = new ArrayList<>();
    try (InputStream in = new FileInputStream(baseTbDir.resolve("configmap.yaml").toFile())) {
      for (Object obj : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) obj;
        docs.add(doc);
      }
    }

    Map<String, Object> scriptsCm =
        docs.stream()
            .filter(d -> "tigerbeetle-scripts".equals(getName(d)))
            .findFirst()
            .orElseThrow();
    Map<String, Object> data = getMap(scriptsCm, "data");
    String initScript = (String) data.get("init.sh");
    String startScript = (String) data.get("start.sh");

    assertThat(initScript)
        .as("Init script must check if data file exists before formatting")
        .contains("if [ -f \"${DATA_FILE}\" ]; then")
        .contains("Format skipped (format-once invariant preserved)")
        .contains("exit 0");

    assertThat(startScript)
        .as("Start script must refuse to start if data file is absent")
        .contains("if [ ! -f \"${DATA_FILE}\" ]; then")
        .contains("exit 1");
    assertThat(startScript)
        .as("A replica binds the entry at its own index, so that entry must be local")
        .contains("a=\"0.0.0.0:${a##*:}\"");
  }

  /**
   * Row 26a (TASK-LOG.md step 99): TigerBeetle 0.17's {@code --development} lowers the default
   * request size limit from 1MiB to 32KiB. The client learns that limit at registration and refuses
   * any {@code create_transfers} over ~253 events client-side ({@code TooMuchData}), so every
   * contribution batch — sized up to {@code LedgerLimits.MAX_LINKED_CHAIN_LENGTH} — sat {@code
   * STAGED} forever on the local cluster while unit tests, whose Testcontainers replica runs
   * without {@code --development}, passed. Any start line that passes {@code --development} must
   * pin the production limit back.
   *
   * <p>Row 26p (TASK-LOG.md step 112): {@code --development} also drops {@code
   * --limit-pipeline-requests} to 0, which cuts the replica to 8 client connections. A ninth client
   * is never accepted, and with every slot taken the replica stops accepting at all, so its
   * liveness probe backs up and kills it. Pinned back to the production default (24, i.e. 32
   * clients — ADR-0033).
   *
   * <p>Row 26q (ADR-0033, 2026-10-03 amendment): compose no longer starts in development mode at
   * all; the k8s branch remains only for {@code TigerBeetleClusterFormationIntegrationTest}.
   */
  @Test
  @DisplayName(
      "Every --development start line pins --limit-request and --limit-pipeline-requests back to"
          + " production")
  void developmentModePinsRequestSizeLimit() throws Exception {
    Path baseTbDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle"));
    Yaml yaml = new Yaml();
    List<Map<String, Object>> docs = new ArrayList<>();
    try (InputStream in = new FileInputStream(baseTbDir.resolve("configmap.yaml").toFile())) {
      for (Object obj : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) obj;
        docs.add(doc);
      }
    }
    Map<String, Object> scriptsCm =
        docs.stream()
            .filter(d -> "tigerbeetle-scripts".equals(getName(d)))
            .findFirst()
            .orElseThrow();
    String startScript = (String) getMap(scriptsCm, "data").get("start.sh");
    assertThat(startScript)
        .as(
            "k8s start.sh: --development must be accompanied by --limit-request=1MiB and"
                + " --limit-pipeline-requests=24")
        .contains("--development --experimental --limit-request=1MiB --limit-pipeline-requests=24");
  }

  /**
   * Row 26q (ADR-0033, 2026-10-03 amendment): every development-mode default found so far made
   * local TigerBeetle behave unlike production (32 KiB requests, 8 clients, zeroed caches), and the
   * replica starts without it on both the kind node's volume and compose's bind mount. Neither
   * local environment may bring it back.
   */
  @Test
  @DisplayName("Neither compose nor the local overlay starts TigerBeetle with --development")
  void localEnvironmentsRunWithoutDevelopmentMode() throws Exception {
    String composeEntrypoint =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/compose/tigerbeetle/entrypoint.sh")));
    assertThat(composeEntrypoint.lines().filter(line -> line.contains("/tigerbeetle ")))
        .as("compose entrypoint.sh must format and start the replica")
        .anyMatch(line -> line.contains(" format "))
        .anyMatch(line -> line.contains(" start "))
        .noneMatch(line -> line.contains("--development"));
    // Production mode allocates ~2,573 MiB at init with a 512MiB grid (~3,085 MiB at the 1GiB
    // default). Compose's 1024M limit OOM-killed the replica at startup, silently, until row 26q.
    assertThat(composeEntrypoint)
        .as("compose entrypoint.sh must shrink the grid cache to fit the container limit")
        .contains("--cache-grid=512MiB");
    assertThat(
            Files.readString(
                Path.of(RepositoryRoot.path("infrastructure/compose/docker-compose.yml"))))
        .as("compose tigerbeetle memory limit must hold a production-mode replica")
        .contains("memory: 3072M");

    Map<String, Object> localCm =
        findByNameAndKind(renderOverlay("local"), "tigerbeetle-config", "ConfigMap");
    assertThat(localCm).isNotNull();
    assertThat(getMap(localCm, "data"))
        .as("the local overlay must not switch the replica into development mode")
        .doesNotContainKey("TIGERBEETLE_DEVELOPMENT");
  }

  @Test
  @DisplayName("PDB allows at most one unavailable replica in base and production")
  void pdbEnforcesMaxUnavailableOne() throws Exception {
    Path baseTbDir = Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle"));
    Yaml yaml = new Yaml();

    Map<String, Object> pdbDoc;
    try (InputStream in = new FileInputStream(baseTbDir.resolve("pdb.yaml").toFile())) {
      pdbDoc = yaml.load(in);
    }

    assertThat(pdbDoc.get("kind")).isEqualTo("PodDisruptionBudget");
    Map<String, Object> spec = getMap(pdbDoc, "spec");
    assertThat(spec.get("maxUnavailable"))
        .as("PDB must set maxUnavailable: 1 so at most 1 replica can be disrupted")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Local overlay configures 1 replica; prod overlay configures 6 replicas and stateful node pool")
  void overlaysApplyTigerBeetleDifferencesCorrectly() throws Exception {
    List<Map<String, Object>> localDocs = renderOverlay("local");
    Map<String, Object> localSs = findByNameAndKind(localDocs, "tigerbeetle", "StatefulSet");
    assertThat(localSs).isNotNull();
    Map<String, Object> localSpec = getMap(localSs, "spec");
    assertThat(localSpec.get("replicas")).isEqualTo(1);

    Map<String, Object> localCm = findByNameAndKind(localDocs, "tigerbeetle-config", "ConfigMap");
    assertThat(localCm).isNotNull();
    Map<String, Object> localData = getMap(localCm, "data");
    assertThat(localData.get("TIGERBEETLE_REPLICA_COUNT")).isEqualTo("1");
    assertThat(localData.get("TIGERBEETLE_ADDRESSES")).isEqualTo("10.96.100.100:3000");

    List<Map<String, Object>> prodDocs = renderOverlay("prod");
    Map<String, Object> prodSs = findByNameAndKind(prodDocs, "tigerbeetle", "StatefulSet");
    assertThat(prodSs).isNotNull();
    Map<String, Object> prodSpec = getMap(prodSs, "spec");
    assertThat(prodSpec.get("replicas")).isEqualTo(6);

    Map<String, Object> template = getMap(prodSpec, "template");
    Map<String, Object> podSpec = getMap(template, "spec");
    List<Map<String, Object>> tolerations = getList(podSpec, "tolerations");
    assertThat(tolerations)
        .anyMatch(
            t ->
                "fondaria.example/node-pool".equals(t.get("key"))
                    && "stateful".equals(t.get("value")));

    List<Map<String, Object>> tsc = getList(podSpec, "topologySpreadConstraints");
    assertThat(tsc.get(0).get("whenUnsatisfiable"))
        .as("Prod TigerBeetle must strictly enforce multi-zone spread")
        .isEqualTo("DoNotSchedule");
  }

  private List<Map<String, Object>> renderOverlay(String overlay) throws Exception {
    Path overlayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/" + overlay));
    String kubectlPath = findExecutable("kubectl");
    assertThat(kubectlPath).as("kubectl must be available").isNotNull();

    Process process = new ProcessBuilder(kubectlPath, "kustomize", overlayPath.toString()).start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    int exit = process.waitFor();
    assertThat(exit).isZero();

    Yaml yaml = new Yaml();
    List<Map<String, Object>> docs = new ArrayList<>();
    for (Object obj : yaml.loadAll(output)) {
      if (obj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) obj;
        docs.add(map);
      }
    }
    return docs;
  }

  private static Map<String, Object> findByNameAndKind(
      List<Map<String, Object>> docs, String name, String kind) {
    for (Map<String, Object> doc : docs) {
      if (kind.equals(doc.get("kind")) && name.equals(getName(doc))) {
        return doc;
      }
    }
    return null;
  }

  private static String getName(Map<String, Object> resource) {
    Map<String, Object> metadata = getMap(resource, "metadata");
    return (String) metadata.get("name");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
    return (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> getList(Map<String, Object> parent, String key) {
    return (List<Map<String, Object>>) parent.get(key);
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
    for (String standardDir : List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")) {
      File file = new File(standardDir, name);
      if (file.canExecute()) {
        return file.getAbsolutePath();
      }
    }
    return null;
  }
}
