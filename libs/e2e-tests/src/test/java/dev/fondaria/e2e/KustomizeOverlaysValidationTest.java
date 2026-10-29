package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 12.2 acceptance tests: 1. Both overlays (local, prod) render and validate. 2. Overlays
 * contain only differences (any identical field belongs in base). 3. Local overlay differences:
 * replica 1, lower requests, NodePort, single TB, self-signed certs, relaxed PDBs. 4. Prod overlay
 * differences: replicas per DESIGN.md §13, HPAs, ACME certs, strict zone spread, node pool
 * tolerations.
 */
class KustomizeOverlaysValidationTest {

  @Test
  @DisplayName("All required overlay files exist")
  void allRequiredOverlayFilesExist() {
    Path overlaysPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays"));

    // Local overlay files: no cert-manager locally (nothing terminates TLS in the demo cluster)
    assertThat(overlaysPath.resolve("local/kustomization.yaml")).isRegularFile();
    assertThat(overlaysPath.resolve("local/storageclass.yaml")).isRegularFile();
    assertThat(overlaysPath.resolve("local/certificates.yaml")).doesNotExist();

    // Prod overlay files (the HPAs live in the base since the Phase 12 fix)
    assertThat(overlaysPath.resolve("prod/kustomization.yaml")).isRegularFile();
    assertThat(overlaysPath.resolve("prod/certificates.yaml")).isRegularFile();
    assertThat(overlaysPath.resolve("prod/storageclass.yaml")).isRegularFile();
    assertThat(overlaysPath.resolve("prod/hpa")).doesNotExist();
  }

  @Test
  @DisplayName("Both local and prod overlays render cleanly with kubectl kustomize")
  void kustomizeBuildSucceedsOnBothOverlays() throws Exception {
    Path overlaysPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays"));
    String kubectlPath = findExecutable("kubectl");

    if (kubectlPath != null) {
      // Build local overlay
      Process localProcess =
          new ProcessBuilder(kubectlPath, "kustomize", overlaysPath.resolve("local").toString())
              .start();
      String localOut =
          new String(localProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int localExit = localProcess.waitFor();
      assertThat(localExit).as("kubectl kustomize local overlay must succeed").isZero();
      assertThat(localOut).contains("kind: Deployment");
      assertThat(localOut).contains("kind: StorageClass");

      // Build prod overlay
      Process prodProcess =
          new ProcessBuilder(kubectlPath, "kustomize", overlaysPath.resolve("prod").toString())
              .start();
      String prodOut =
          new String(prodProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int prodExit = prodProcess.waitFor();
      assertThat(prodExit).as("kubectl kustomize prod overlay must succeed").isZero();
      assertThat(prodOut).contains("kind: Deployment");
      assertThat(prodOut).contains("kind: ClusterIssuer");
      assertThat(prodOut).contains("kind: HorizontalPodAutoscaler");
    }
  }

  @Test
  @DisplayName("Local overlay applies all expected differences")
  void localOverlayAppliesExpectedDifferences() throws Exception {
    List<Map<String, Object>> renderedDocs = renderOverlay("local");
    assertThat(renderedDocs).isNotEmpty();

    // 1. All Deployments have replica count 1 (9 microservices + 3 observability workloads --
    // otel-collector, grafana, kube-state-metrics -- + Keycloak, Task D.3)
    List<Map<String, Object>> deployments = filterByKind(renderedDocs, "Deployment");
    assertThat(deployments).hasSize(13);
    for (Map<String, Object> dep : deployments) {
      String name = getName(dep);
      Map<String, Object> spec = getMap(dep, "spec");
      // ADR-0032 / row 26g: projection-worker runs 3 locally so the scale-out is exercised;
      // statement-worker runs 2 so the month-end's statements render on two pods.
      int expectedReplicas =
          switch (name) {
            case "projection-worker" -> 3;
            case "statement-worker" -> 2;
            default -> 1;
          };
      assertThat(spec.get("replicas"))
          .as("Deployment %s must have replicas: %d in local overlay", name, expectedReplicas)
          .isEqualTo(expectedReplicas);

      // 2. Resource requests are lowered to 50m CPU and 256Mi memory
      Map<String, Object> template = getMap(spec, "template");
      Map<String, Object> podSpec = getMap(template, "spec");
      List<Map<String, Object>> containers = getList(podSpec, "containers");
      Map<String, Object> resources = getMap(containers.get(0), "resources");
      Map<String, Object> requests = getMap(resources, "requests");
      assertThat(requests.get("cpu"))
          .as("CPU request for %s in local overlay", name)
          .isEqualTo("50m");
      assertThat(requests.get("memory"))
          .as("Memory request for %s in local overlay", name)
          .isEqualTo("128Mi");
    }

    // 3. Relaxed PDBs: minAvailable: 0 (10 service/infra PDBs + object-store + 6 observability PDBs
    // + Keycloak, Task D.3)
    List<Map<String, Object>> pdbs = filterByKind(renderedDocs, "PodDisruptionBudget");
    assertThat(pdbs).hasSize(18);
    for (Map<String, Object> pdb : pdbs) {
      String name = getName(pdb);
      Map<String, Object> spec = getMap(pdb, "spec");
      assertThat(spec.get("minAvailable"))
          .as("PDB %s must have minAvailable: 0 in local overlay", name)
          .isEqualTo(0);
      assertThat(spec.get("maxUnavailable"))
          .as("PDB %s must not specify maxUnavailable when minAvailable is set", name)
          .isNull();
    }

    // 4. NodePort services: Grafana only, plus the gateway's own (below). Pre-existing defect,
    // found running this suite for Task 10.2: this map still expected contribution-api,
    // balance-api and valuation-api's own NodePorts (30080-30082), which Task 10.1 (ADR-0028)
    // removed -- fondaria-gateway is now the one external entry point, on its own NodePort 30443
    // (GatewayBaseValidationTest#localOverlayRemovesDirectApiNodePorts already covers that one).
    // This test never caught the removal because it does not need a live cluster to fail, only
    // `make verify` -- see TASK-LOG.md.
    Map<String, Integer> expectedNodePorts = Map.of("grafana", 30001);

    for (Map.Entry<String, Integer> entry : expectedNodePorts.entrySet()) {
      Map<String, Object> svc = findByNameAndKind(renderedDocs, entry.getKey(), "Service");
      assertThat(svc).as("Service %s must exist", entry.getKey()).isNotNull();
      Map<String, Object> spec = getMap(svc, "spec");
      assertThat(spec.get("type"))
          .as("Service %s must be NodePort in local overlay", entry.getKey())
          .isEqualTo("NodePort");
      List<Map<String, Object>> ports = getList(spec, "ports");
      assertThat(ports.get(0).get("nodePort"))
          .as("Service %s nodePort", entry.getKey())
          .isEqualTo(entry.getValue());
    }

    // 5. Single-replica TigerBeetle configuration in ConfigMaps
    List<String> tbConfigMaps =
        List.of(
            "contribution-api-config",
            "allotment-job-config",
            "balance-api-config",
            "eod-job-config",
            "valuation-api-config");
    for (String cmName : tbConfigMaps) {
      Map<String, Object> cm = findByNameAndKind(renderedDocs, cmName, "ConfigMap");
      assertThat(cm).as("ConfigMap %s must exist", cmName).isNotNull();
      Map<String, Object> data = getMap(cm, "data");
      assertThat(data.get("TIGERBEETLE_ADDRESSES"))
          .as("TIGERBEETLE_ADDRESSES in %s (one replica, one fixed ClusterIP)", cmName)
          .isEqualTo("10.96.100.100:3000");
    }

    Map<String, Object> cdcCm =
        findByNameAndKind(renderedDocs, "tigerbeetle-cdc-config", "ConfigMap");
    assertThat(cdcCm).isNotNull();
    Map<String, Object> cdcData = getMap(cdcCm, "data");
    assertThat(cdcData.get("CDC_TIGERBEETLE_ADDRESSES"))
        .as("CDC_TIGERBEETLE_ADDRESSES in tigerbeetle-cdc-config")
        .isEqualTo("10.96.100.100:3000");

    // 5a. The demo's close window: the close waits for 17:00 on the simulated clock and then runs
    // on a short tick, so `demo-tools close` sees the day signed off within a minute or two.
    Map<String, Object> eodData =
        getMap(findByNameAndKind(renderedDocs, "eod-job-config", "ConfigMap"), "data");
    assertThat(eodData.get("CLOSE_WINDOW_START")).isEqualTo("17:00");
    for (String interval :
        List.of(
            "RECONCILIATION_POLL_INTERVAL",
            "CUSTODIAN_RECONCILIATION_POLL_INTERVAL",
            "SNAPSHOT_POLL_INTERVAL",
            "SIGNOFF_POLL_INTERVAL")) {
      assertThat(eodData.get(interval)).as("%s in eod-job-config", interval).isEqualTo("20s");
    }

    // 6. Local storage: the base's tigerbeetle-nvme class is kind's local-path provisioner
    Map<String, Object> storageClass =
        findByNameAndKind(renderedDocs, "tigerbeetle-nvme", "StorageClass");
    assertThat(storageClass).as("Local StorageClass must exist").isNotNull();
    assertThat(storageClass.get("provisioner")).isEqualTo("rancher.io/local-path");

    // 7. Local credentials: generated Secrets replace the base's ExternalSecrets, same names
    assertThat(filterByKind(renderedDocs, "ExternalSecret"))
        .as("no External Secrets Operator locally")
        .isEmpty();
    for (String secret :
        List.of(
            "object-store-credentials",
            "grafana-admin-secret",
            "balance-api-secret",
            "valkey-credentials",
            "keycloak-client-secrets",
            "fondaria-gateway-tls")) {
      assertThat(findByNameAndKind(renderedDocs, secret, "Secret"))
          .as("generated local Secret %s", secret)
          .isNotNull();
    }
    // The demo realm users exist only here, mounted beside the base realm (TASK-LOG step 118).
    Map<String, Object> demoUsers =
        findByNameAndKind(renderedDocs, "keycloak-demo-users", "ConfigMap");
    assertThat(demoUsers).as("local demo users ConfigMap").isNotNull();
    assertThat(getMap(demoUsers, "data")).containsKey("fondaria-users-0.json");
    assertThat(findByNameAndKind(renderedDocs, "keycloak", "Deployment").toString())
        .contains("keycloak-realm")
        .contains("keycloak-demo-users");
    assertThat(filterByKind(renderedDocs, "Certificate")).isEmpty();
    assertThat(filterByKind(renderedDocs, "Issuer")).isEmpty();

    // 8. Everything is namespaced into fondaria
    for (Map<String, Object> doc : renderedDocs) {
      Map<String, Object> metadata = getMap(doc, "metadata");
      // ClusterRole/ClusterRoleBinding added for Task 10.2's prometheus-pod-discovery: it is
      // cluster-scoped by necessity (Prometheus needs to list pods in envoy-gateway-system, a
      // namespace this tree does not own), so it carries no metadata.namespace at all -- its
      // ClusterRoleBinding's *subject* still names `fondaria` (serviceaccount.yaml's own comment),
      // which is a different field this loop does not inspect.
      if (!List.of(
              "PriorityClass", "StorageClass", "Namespace", "ClusterRole", "ClusterRoleBinding")
          .contains(doc.get("kind"))) {
        assertThat(metadata.get("namespace"))
            .as("%s/%s must be in namespace fondaria", doc.get("kind"), metadata.get("name"))
            .isEqualTo("fondaria");
      }
    }

    // 9. Task D.3: Local HPAs capped at maxReplicas 3 with a 300s scaleUp stabilization window
    List<Map<String, Object>> hpas = filterByKind(renderedDocs, "HorizontalPodAutoscaler");
    assertThat(hpas).hasSize(3);
    for (Map<String, Object> hpa : hpas) {
      String name = getName(hpa);
      Map<String, Object> spec = getMap(hpa, "spec");
      assertThat(spec.get("minReplicas"))
          .as("HPA %s minReplicas in local overlay", name)
          .isEqualTo(1);
      assertThat(spec.get("maxReplicas"))
          .as("HPA %s maxReplicas in local overlay", name)
          .isEqualTo(3);
      Map<String, Object> behavior = getMap(spec, "behavior");
      assertThat(behavior).as("HPA %s behavior in local overlay", name).isNotNull();
      Map<String, Object> scaleUp = getMap(behavior, "scaleUp");
      assertThat(scaleUp).as("HPA %s scaleUp behavior in local overlay", name).isNotNull();
      assertThat(scaleUp.get("stabilizationWindowSeconds"))
          .as("HPA %s scaleUp stabilization window", name)
          .isEqualTo(300);
    }
  }

  @Test
  @DisplayName("Prod overlay applies all expected differences")
  void prodOverlayAppliesExpectedDifferences() throws Exception {
    List<Map<String, Object>> renderedDocs = renderOverlay("prod");
    assertThat(renderedDocs).isNotEmpty();

    // 0. No close window in production: every close sweep runs on every tick, as before.
    assertThat(
            getMap(findByNameAndKind(renderedDocs, "eod-job-config", "ConfigMap"), "data")
                .get("CLOSE_WINDOW_START"))
        .isEqualTo("");

    // 1. Replica counts per DESIGN.md §13 (services differing from base)
    Map<String, Integer> expectedReplicas =
        Map.of(
            "contribution-api", 3,
            "balance-api", 3,
            "statement-worker", 4,
            "otel-collector", 2,
            "grafana", 2);

    for (Map.Entry<String, Integer> entry : expectedReplicas.entrySet()) {
      Map<String, Object> dep = findByNameAndKind(renderedDocs, entry.getKey(), "Deployment");
      assertThat(dep).as("Deployment %s must exist", entry.getKey()).isNotNull();
      Map<String, Object> spec = getMap(dep, "spec");
      assertThat(spec.get("replicas"))
          .as("Deployment %s replicas in prod overlay", entry.getKey())
          .isEqualTo(entry.getValue());
    }

    // Workloads matching base replicas are NOT altered
    Map<String, Object> valDep = findByNameAndKind(renderedDocs, "valuation-api", "Deployment");
    assertThat(getMap(valDep, "spec").get("replicas")).isEqualTo(2);

    Map<String, Object> projDep =
        findByNameAndKind(renderedDocs, "projection-worker", "Deployment");
    assertThat(getMap(projDep, "spec").get("replicas")).isEqualTo(2);

    // 2. HPAs present for contribution-api, balance-api, and valuation-api (Task 12.5). The
    // maxima were 10/10/8 until ADR-0033 (row 26p) sized them against TigerBeetle's 32-client
    // limit; LedgerClientBudgetValidationTest enforces the total, these pin the split.
    List<Map<String, Object>> hpas = filterByKind(renderedDocs, "HorizontalPodAutoscaler");
    assertThat(hpas).hasSize(3);
    Map<String, Object> hpaContrib =
        findByNameAndKind(renderedDocs, "contribution-api", "HorizontalPodAutoscaler");
    assertThat(hpaContrib).isNotNull();
    Map<String, Object> hpaContribSpec = getMap(hpaContrib, "spec");
    assertThat(hpaContribSpec.get("minReplicas")).isEqualTo(3);
    assertThat(hpaContribSpec.get("maxReplicas")).isEqualTo(6);

    Map<String, Object> hpaBalance =
        findByNameAndKind(renderedDocs, "balance-api", "HorizontalPodAutoscaler");
    assertThat(hpaBalance).isNotNull();
    Map<String, Object> hpaBalanceSpec = getMap(hpaBalance, "spec");
    assertThat(hpaBalanceSpec.get("minReplicas")).isEqualTo(3);
    assertThat(hpaBalanceSpec.get("maxReplicas")).isEqualTo(8);

    Map<String, Object> hpaValuation =
        findByNameAndKind(renderedDocs, "valuation-api", "HorizontalPodAutoscaler");
    assertThat(hpaValuation).isNotNull();
    Map<String, Object> hpaValuationSpec = getMap(hpaValuation, "spec");
    assertThat(hpaValuationSpec.get("minReplicas")).isEqualTo(3);
    assertThat(hpaValuationSpec.get("maxReplicas")).isEqualTo(4);

    // 3. Strict zone spread: whenUnsatisfiable: DoNotSchedule across all Deployments
    List<Map<String, Object>> deployments = filterByKind(renderedDocs, "Deployment");
    for (Map<String, Object> dep : deployments) {
      String name = getName(dep);
      Map<String, Object> spec = getMap(dep, "spec");
      Map<String, Object> template = getMap(spec, "template");
      Map<String, Object> podSpec = getMap(template, "spec");
      List<Map<String, Object>> tsc = getList(podSpec, "topologySpreadConstraints");
      assertThat(tsc.get(0).get("whenUnsatisfiable"))
          .as("Deployment %s must use DoNotSchedule in prod", name)
          .isEqualTo("DoNotSchedule");

      // 4. Node pool tolerations and affinity
      List<Map<String, Object>> tolerations = getList(podSpec, "tolerations");
      assertThat(tolerations)
          .as("Deployment %s must have stateless node pool toleration", name)
          .anyMatch(
              t ->
                  "fondaria.example/node-pool".equals(t.get("key"))
                      && "stateless".equals(t.get("value")));

      Map<String, Object> affinity = getMap(podSpec, "affinity");
      assertThat(affinity).as("Deployment %s must have nodeAffinity", name).isNotNull();
    }

    // 4b. Observability StatefulSets: node pool tolerations and affinity in prod
    for (String obs : List.of("prometheus", "tempo", "loki")) {
      Map<String, Object> ss = findByNameAndKind(renderedDocs, obs, "StatefulSet");
      assertThat(ss).as("StatefulSet %s must exist in prod", obs).isNotNull();
      Map<String, Object> ssSpec = getMap(ss, "spec");
      Map<String, Object> ssTemplate = getMap(ssSpec, "template");
      Map<String, Object> ssPodSpec = getMap(ssTemplate, "spec");
      List<Map<String, Object>> tolerations = getList(ssPodSpec, "tolerations");
      assertThat(tolerations)
          .as("StatefulSet %s must have stateful node pool toleration", obs)
          .anyMatch(
              t ->
                  "fondaria.example/node-pool".equals(t.get("key"))
                      && "stateful".equals(t.get("value")));
      Map<String, Object> affinity = getMap(ssPodSpec, "affinity");
      assertThat(affinity).as("StatefulSet %s must have nodeAffinity", obs).isNotNull();
    }

    // 5. Production certificates: ClusterIssuer (letsencrypt-prod) and Certificate
    Map<String, Object> issuer =
        findByNameAndKind(renderedDocs, "letsencrypt-prod", "ClusterIssuer");
    assertThat(issuer).as("Prod ClusterIssuer must exist").isNotNull();
    Map<String, Object> issuerSpec = getMap(issuer, "spec");
    assertThat(issuerSpec.get("acme")).isNotNull();

    Map<String, Object> cert = findByNameAndKind(renderedDocs, "fondaria-prod-tls", "Certificate");
    assertThat(cert).as("Prod Certificate must exist").isNotNull();
    Map<String, Object> certSpec = getMap(cert, "spec");
    assertThat(certSpec.get("commonName")).isEqualTo("fondaria.example");
  }

  @Test
  @DisplayName("Overlays contain only differences: identical fields belong in base")
  void overlaysContainOnlyDifferencesInvariant() throws Exception {
    Path overlaysPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays"));
    Yaml yaml = new Yaml();

    // Parse local and prod kustomization files
    Map<String, Object> localKust;
    try (InputStream in =
        new FileInputStream(overlaysPath.resolve("local/kustomization.yaml").toFile())) {
      localKust = yaml.load(in);
    }
    Map<String, Object> prodKust;
    try (InputStream in =
        new FileInputStream(overlaysPath.resolve("prod/kustomization.yaml").toFile())) {
      prodKust = yaml.load(in);
    }

    List<String> redundantPatches = findRedundantPatches(localKust, prodKust);
    assertThat(redundantPatches)
        .as("Overlays must not contain identical patches; identical definitions belong in base")
        .isEmpty();
  }

  @Test
  @DisplayName("Redundancy detector flags identical patches even with differing whitespace/format")
  void redundancyDetectorFlagsIdenticalPatches() {
    Map<String, Object> patchA =
        Map.of(
            "target",
            Map.of("kind", "Deployment", "name", "payment-api"),
            "patch",
            "apiVersion: apps/v1\nkind: Deployment\nspec:\n  paused: true\n");

    Map<String, Object> patchB =
        Map.of(
            "target",
            Map.of("kind", "Deployment", "name", "payment-api"),
            "patch",
            "apiVersion: \"apps/v1\"\nkind: 'Deployment'\nspec: { paused: true }");

    Map<String, Object> kust1 = Map.of("patches", List.of(patchA));
    Map<String, Object> kust2 = Map.of("patches", List.of(patchB));

    List<String> redundant = findRedundantPatches(kust1, kust2);
    assertThat(redundant).hasSize(1);
    assertThat(redundant.get(0)).contains("Deployment");
  }

  @Test
  @DisplayName("No plaintext secrets or kind: Secret exist in overlays")
  void noPlaintextSecretsInOverlays() throws Exception {
    Path overlaysPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays"));

    try (var stream = Files.walk(overlaysPath)) {
      for (Path p : stream.filter(Files::isRegularFile).toList()) {
        if (p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")) {
          String content = Files.readString(p);
          assertThat(content)
              .as("File %s must not declare 'kind: Secret'", p)
              .doesNotContain("kind: Secret\n")
              .doesNotContain("kind: Secret\r\n");

          // Ensure no plaintext passwords
          assertThat(content)
              .as("File %s must not contain plaintext passwords", p)
              .doesNotContainIgnoringCase("password:")
              .doesNotContainIgnoringCase("passwd:");

          // The local overlay is the one place dev credentials are allowed to live, as
          // compose's `${VAR:-value}` defaults are (docs/ENGINEERING-RULES.md "Configuration");
          // prod never
          // generates a Secret from a literal.
          if (p.toString().contains("/prod/")) {
            assertThat(content)
                .as("File %s must not generate Secrets from literals", p)
                .doesNotContain("secretGenerator");
          }
        }
      }
    }
  }

  // --- Helper methods ---

  private List<String> findRedundantPatches(Map<String, Object> kust1, Map<String, Object> kust2) {
    List<String> violations = new ArrayList<>();
    List<Map<String, Object>> patches1 = getList(kust1, "patches");
    List<Map<String, Object>> patches2 = getList(kust2, "patches");

    if (patches1 == null || patches2 == null) return violations;
    Yaml yaml = new Yaml();

    for (Map<String, Object> p1 : patches1) {
      Map<String, Object> target1 = getMap(p1, "target");
      String patch1Text = (String) p1.get("patch");
      Object parsed1 = patch1Text != null ? yaml.load(patch1Text) : null;

      for (Map<String, Object> p2 : patches2) {
        Map<String, Object> target2 = getMap(p2, "target");
        String patch2Text = (String) p2.get("patch");
        Object parsed2 = patch2Text != null ? yaml.load(patch2Text) : null;

        if (target1 != null && target1.equals(target2)) {
          if (parsed1 != null && parsed1.equals(parsed2)) {
            violations.add("Duplicate identical patch on target " + target1);
          }
        }
      }
    }
    return violations;
  }

  private List<Map<String, Object>> renderOverlay(String overlayName) throws Exception {
    Path overlayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/" + overlayName));
    String kubectlPath = findExecutable("kubectl");
    Yaml yaml = new Yaml();

    if (kubectlPath != null) {
      Process process =
          new ProcessBuilder(kubectlPath, "kustomize", overlayPath.toString()).start();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      assertThat(exit).as("kustomize build on %s must succeed", overlayName).isZero();

      List<Map<String, Object>> result = new ArrayList<>();
      for (Object doc : yaml.loadAll(out)) {
        if (doc instanceof Map<?, ?> map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> typedMap = (Map<String, Object>) map;
          result.add(typedMap);
        }
      }
      return result;
    }
    return List.of();
  }

  private static List<Map<String, Object>> filterByKind(
      List<Map<String, Object>> docs, String kind) {
    return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
  }

  private static Map<String, Object> findByNameAndKind(
      List<Map<String, Object>> docs, String name, String kind) {
    return docs.stream()
        .filter(d -> kind.equals(d.get("kind")) && name.equals(getName(d)))
        .findFirst()
        .orElse(null);
  }

  private static String getName(Map<String, Object> doc) {
    Map<String, Object> meta = getMap(doc, "metadata");
    return meta != null ? (String) meta.get("name") : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
    if (parent == null) return null;
    return (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> getList(Map<String, Object> parent, String key) {
    if (parent == null) return null;
    return (List<T>) parent.get(key);
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
