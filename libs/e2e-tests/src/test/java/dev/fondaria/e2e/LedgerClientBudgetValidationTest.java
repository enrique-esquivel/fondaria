package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-0033 (row 26p, TASK-LOG.md step 112): every pod holding a TigerBeetle client takes one of the
 * replica's connection slots for its whole life, and the replica accepts only {@value
 * #CLIENT_SLOTS} clients (TigerBeetle 0.17's production default, which local runs use too since row
 * 26q). With every slot taken the replica stops calling {@code accept()} at all: a further client
 * is never served, and the kubelet's own TCP probes back up until liveness kills a healthy replica
 * — observed twice on the local cluster.
 *
 * <p>So each overlay, rendered, must fit its worst case under the limit with room left for the
 * short-lived connections that are not clients ({@value #TRANSIENT_HEADROOM}: the replica's own
 * liveness/readiness probes and each service's readiness connect). The worst case per workload is
 * its HPA's {@code maxReplicas} (or its fixed {@code replicas}) plus the extra pods a rollout
 * surges, all at once.
 *
 * <p>A ledger client is any workload that reads its environment from a ConfigMap carrying a key
 * ending in {@code TIGERBEETLE_ADDRESSES} (the CDC's is {@code CDC_TIGERBEETLE_ADDRESSES}) —
 * detected, not listed, so a new service that starts talking to the ledger is counted the day it is
 * deployed.
 */
class LedgerClientBudgetValidationTest {

  static final int CLIENT_SLOTS = 32;
  static final int TRANSIENT_HEADROOM = 4;

  @ParameterizedTest(name = "{0} overlay fits the TigerBeetle client budget")
  @ValueSource(strings = {"local", "prod"})
  void everyOverlayFitsItsLedgerClientsUnderTheReplicasConnectionLimit(String overlay)
      throws Exception {
    List<Map<String, Object>> docs = render(overlay);

    Set<String> ledgerConfigMaps = new HashSet<>();
    for (Map<String, Object> doc : docs) {
      if ("ConfigMap".equals(doc.get("kind"))) {
        Map<String, Object> data = map(doc, "data");
        if (data != null
            && data.keySet().stream().anyMatch(key -> key.endsWith("TIGERBEETLE_ADDRESSES"))) {
          ledgerConfigMaps.add(name(doc));
        }
      }
    }
    Map<String, Integer> hpaMax = new HashMap<>();
    for (Map<String, Object> doc : docs) {
      if ("HorizontalPodAutoscaler".equals(doc.get("kind"))) {
        Map<String, Object> spec = map(doc, "spec");
        hpaMax.put(
            (String) map(spec, "scaleTargetRef").get("name"), (Integer) spec.get("maxReplicas"));
      }
    }

    Map<String, Integer> worstCaseByWorkload = new LinkedHashMap<>();
    for (Map<String, Object> doc : docs) {
      if (!"Deployment".equals(doc.get("kind")) && !"StatefulSet".equals(doc.get("kind"))) {
        continue;
      }
      if ("tigerbeetle".equals(name(doc))) {
        continue; // the replicas themselves, not clients
      }
      Map<String, Object> spec = map(doc, "spec");
      Map<String, Object> podSpec = map(map(spec, "template"), "spec");
      if (!readsAnyConfigMap(podSpec, ledgerConfigMaps)) {
        continue;
      }
      int replicas = (Integer) spec.getOrDefault("replicas", 1);
      int max = hpaMax.getOrDefault(name(doc), replicas);
      worstCaseByWorkload.put(name(doc), max + surge(doc, max));
    }

    assertThat(worstCaseByWorkload)
        .as("%s: the ledger clients this test detects", overlay)
        .containsKeys(
            "allotment-job",
            "balance-api",
            "contribution-api",
            "eod-job",
            "tigerbeetle-cdc",
            "valuation-api");
    int total = worstCaseByWorkload.values().stream().mapToInt(Integer::intValue).sum();
    assertThat(total)
        .as(
            "%s: worst-case TigerBeetle clients %s must leave %d of the replica's %d slots free",
            overlay, worstCaseByWorkload, TRANSIENT_HEADROOM, CLIENT_SLOTS)
        .isLessThanOrEqualTo(CLIENT_SLOTS - TRANSIENT_HEADROOM);
  }

  /** Extra pods a rollout adds: none for {@code Recreate}, else {@code maxSurge} resolved. */
  private static int surge(Map<String, Object> workload, int replicas) {
    if ("StatefulSet".equals(workload.get("kind"))) {
      return 0; // a StatefulSet replaces pods one at a time, never alongside
    }
    Map<String, Object> strategy = map(map(workload, "spec"), "strategy");
    if (strategy != null && "Recreate".equals(strategy.get("type"))) {
      return 0;
    }
    Object maxSurge =
        strategy == null || map(strategy, "rollingUpdate") == null
            ? "25%" // Kubernetes' default
            : map(strategy, "rollingUpdate").getOrDefault("maxSurge", "25%");
    if (maxSurge instanceof Integer count) {
      return count;
    }
    String percent = (String) maxSurge;
    int pct = Integer.parseInt(percent.substring(0, percent.length() - 1));
    return (replicas * pct + 99) / 100; // Kubernetes rounds a surge percentage up
  }

  private static boolean readsAnyConfigMap(Map<String, Object> podSpec, Set<String> names) {
    List<Map<String, Object>> containers = new ArrayList<>(list(podSpec, "containers"));
    if (list(podSpec, "initContainers") != null) {
      containers.addAll(list(podSpec, "initContainers"));
    }
    for (Map<String, Object> container : containers) {
      List<Map<String, Object>> envFrom = list(container, "envFrom");
      if (envFrom != null) {
        for (Map<String, Object> source : envFrom) {
          Map<String, Object> ref = map(source, "configMapRef");
          if (ref != null && names.contains((String) ref.get("name"))) {
            return true;
          }
        }
      }
      List<Map<String, Object>> env = list(container, "env");
      if (env != null) {
        for (Map<String, Object> variable : env) {
          Map<String, Object> ref = map(map(variable, "valueFrom"), "configMapKeyRef");
          if (ref != null && names.contains((String) ref.get("name"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static List<Map<String, Object>> render(String overlay) throws Exception {
    String kubectl = findExecutable("kubectl");
    assumeTrue(kubectl != null, "kubectl is needed to render the overlays");
    Process process =
        new ProcessBuilder(
                kubectl, "kustomize", RepositoryRoot.path("infrastructure/k8s/overlays/" + overlay))
            .start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("kubectl kustomize %s", overlay).isZero();
    List<Map<String, Object>> docs = new ArrayList<>();
    for (Object doc : new Yaml().loadAll(out)) {
      if (doc instanceof Map<?, ?>) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) doc;
        docs.add(typed);
      }
    }
    return docs;
  }

  private static String name(Map<String, Object> doc) {
    return (String) map(doc, "metadata").get("name");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    return parent == null ? null : (Map<String, Object>) parent.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
    return parent == null ? null : (List<Map<String, Object>>) parent.get(key);
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
