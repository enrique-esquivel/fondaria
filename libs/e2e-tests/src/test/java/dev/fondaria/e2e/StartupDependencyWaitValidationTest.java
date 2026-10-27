package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.fondaria.bridge.listen.BridgeListener;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-0037: every workload waits for what it depends on before it starts.
 *
 * <p>A workload's dependencies are read from what its own containers already reference -- the
 * operator Secrets for Postgres and RabbitMQ, {@code valkey-credentials}, {@code
 * object-store-credentials}, a ConfigMap carrying TigerBeetle addresses -- so a new workload, or a
 * new dependency on an old one, fails here until it has the matching {@code wait-for-*} init
 * container. The base is read from its files and always checked; the four overlays are rendered
 * when {@code kubectl} is on the PATH, to prove no patch drops or breaks a wait.
 */
class StartupDependencyWaitValidationTest {

  private static final Path BASE = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));

  private static final String SCRIPTS_CONFIG_MAP = "startup-waits";

  /** Linkerd's default proxy UID: linkerd-init's iptables rules let it bypass the proxy. */
  private static final long LINKERD_PROXY_UID = 2102;

  /**
   * Dependencies a workload references but does not need in order to start, each with its reason.
   * projection-worker's Valkey is CDC-boundary trace correlation: optional, and absent disables
   * only the trace links ({@code TraceRedisProperties}).
   */
  private static final Map<String, Set<String>> NOT_A_STARTUP_DEPENDENCY =
      Map.of("projection-worker", Set.of("valkey"));

  private static final List<String> NATIVE_SIDECAR_ANNOTATIONS =
      List.of(
          "config.alpha.linkerd.io/proxy-enable-native-sidecar",
          "config.beta.linkerd.io/proxy-enable-native-sidecar");

  @Test
  @DisplayName("Every base workload waits for each dependency its containers reference")
  void everyDependencyHasItsWait() throws Exception {
    List<Map<String, Object>> docs = baseDocuments();
    Map<String, Map<String, Object>> workloads = workloads(docs);
    assertThat(workloads)
        .as("the platform's workloads, read from the base")
        .containsKeys(
            "contribution-api",
            "balance-api",
            "valuation-api",
            "allotment-job",
            "eod-job",
            "statement-worker",
            "projection-worker",
            "amqp-fanout",
            "tigerbeetle-cdc",
            "keycloak",
            "rabbitmq-topology",
            "object-store-buckets");

    for (var entry : workloads.entrySet()) {
      String workload = entry.getKey();
      Map<String, Object> podSpec = podSpec(entry.getValue());
      Set<String> needed = dependencies(workload, podSpec, configMaps(docs));
      Set<String> waits = waitsOf(podSpec);
      assertThat(waits)
          .as("%s references %s, so it must wait for each before it starts", workload, needed)
          .containsAll(needed.stream().map(d -> "wait-for-" + d).toList());
    }
  }

  @Test
  @DisplayName("A wait reads its target from the ConfigMap its workload connects with")
  void waitsReadTheWorkloadsOwnConfiguration() throws Exception {
    Map<String, Map<String, Object>> workloads = workloads(baseDocuments());
    for (var entry : workloads.entrySet()) {
      Map<String, Object> podSpec = podSpec(entry.getValue());
      Set<String> appConfigMaps = new LinkedHashSet<>();
      for (Map<String, Object> container : list(podSpec, "containers")) {
        for (Map<String, Object> source : list(container, "envFrom")) {
          Map<String, Object> ref = map(source, "configMapRef");
          if (ref != null) {
            appConfigMaps.add((String) ref.get("name"));
          }
        }
      }
      for (Map<String, Object> init : list(podSpec, "initContainers")) {
        String name = (String) init.get("name");
        String target =
            switch (name) {
              case "wait-for-postgres" ->
                  env(init).containsKey("POSTGRES_URL") ? "POSTGRES_URL" : "PGHOST";
              case "wait-for-valkey" -> "VALKEY_URL";
              case "wait-for-rabbitmq" -> "RABBITMQ_HOST";
              case "wait-for-object-store" -> "OBJECT_STORE_ENDPOINT";
              case "wait-for-tigerbeetle" -> "TIGERBEETLE_ADDRESSES";
              default -> null;
            };
        if (target == null || appConfigMaps.isEmpty()) {
          continue; // not a wait, or a setup Job that names its target literally
        }
        Map<String, Object> ref = map(map(env(init).get(target), "valueFrom"), "configMapKeyRef");
        assertThat(ref)
            .as("%s/%s must read %s from a ConfigMap", entry.getKey(), name, target)
            .isNotNull();
        assertThat(appConfigMaps)
            .as("%s/%s reads %s from the workload's own ConfigMap", entry.getKey(), name, target)
            .contains((String) ref.get("name"));
      }
    }
  }

  @Test
  @DisplayName("Wait containers are pinned, non-root, read-only, bounded and run a shipped script")
  void waitContainersAreHardened() throws Exception {
    List<Map<String, Object>> docs = baseDocuments();
    Map<String, String> scripts = scripts(docs);
    for (var entry : workloads(docs).entrySet()) {
      String workload = entry.getKey();
      Map<String, Object> podSpec = podSpec(entry.getValue());
      assertThat(map(podSpec, "securityContext").get("runAsNonRoot"))
          .as("%s runs as non-root", workload)
          .isEqualTo(true);
      for (Map<String, Object> init : list(podSpec, "initContainers")) {
        String name = (String) init.get("name");
        if (!name.startsWith("wait-for-")) {
          continue;
        }
        String where = workload + "/" + name;
        assertThat((String) init.get("image")).as("%s image", where).contains("@sha256:");
        Map<String, Object> security = map(init, "securityContext");
        assertThat(security.get("readOnlyRootFilesystem")).as(where).isEqualTo(true);
        assertThat(security.get("allowPrivilegeEscalation")).as(where).isEqualTo(false);
        assertThat(map(security, "capabilities").get("drop")).as(where).isEqualTo(List.of("ALL"));
        assertThat(map(map(init, "resources"), "limits")).as("%s limits", where).isNotNull();
        assertThat(map(map(init, "resources"), "requests")).as("%s requests", where).isNotNull();

        Object uid = security.get("runAsUser");
        if ("wait-for-tigerbeetle".equals(name)) {
          assertThat(uid)
              .as("%s bypasses the proxy: through it, a TCP connect always succeeds", where)
              .isEqualTo((int) LINKERD_PROXY_UID);
        } else {
          assertThat(uid).as("%s keeps the pod's user", where).isNull();
        }

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) init.get("command");
        String script = command.get(command.size() - 1);
        assertThat(script).as("%s command", where).startsWith("/startup-waits/");
        assertThat(scripts)
            .as("%s runs a script the startup-waits ConfigMap ships", where)
            .containsKey(script.substring("/startup-waits/".length()));
        assertThat(mountPaths(init)).as("%s mounts", where).contains("/startup-waits", "/tmp");
      }
    }
    for (var script : scripts.entrySet()) {
      if (script.getKey().endsWith(".sh") && !"lib.sh".equals(script.getKey())) {
        assertThat(script.getValue())
            .as("%s fails on an unset variable and gives up at a deadline", script.getKey())
            .contains("set -eu")
            .contains(". /startup-waits/lib.sh")
            .contains("wait_for ");
      }
    }
    assertThat(scripts.get("lib.sh")).contains("WAIT_TIMEOUT_SECONDS").contains("exit 1");
  }

  @Test
  @DisplayName("A meshed pod with waits runs Linkerd's proxy as a native sidecar")
  void meshedWaitsRunBehindANativeSidecarProxy() throws Exception {
    for (var entry : workloads(baseDocuments()).entrySet()) {
      Map<String, Object> template = map(map(entry.getValue(), "spec"), "template");
      Map<String, Object> annotations = map(map(template, "metadata"), "annotations");
      boolean meshed =
          annotations != null && "enabled".equals(annotations.get("linkerd.io/inject"));
      if (!meshed || waitsOf(podSpec(entry.getValue())).isEmpty()) {
        continue;
      }
      for (String annotation : NATIVE_SIDECAR_ANNOTATIONS) {
        assertThat(annotations.get(annotation))
            .as(
                "%s is meshed and its init containers call meshed datastores: without %s the"
                    + " proxy starts after them and their traffic has nowhere to go",
                entry.getKey(), annotation)
            .isEqualTo("true");
      }
    }
  }

  @Test
  @DisplayName("No publisher starts before the queues behind its exchange are bound")
  void publishersWaitForTheirBindings() throws Exception {
    List<Map<String, Object>> docs = baseDocuments();
    Map<String, Map<String, Object>> workloads = workloads(docs);
    Map<String, String> projection = configMaps(docs).get("projection-worker-config");

    // amqp-fanout republishes what it consumes from bridge.ledger into the projection's exchanges.
    Map<String, Map<String, Object>> fanout = waitEnv(workloads.get("amqp-fanout"));
    assertThat(literal(fanout, "WAIT_RABBITMQ_BINDINGS").split(" "))
        .contains(
            "tigerbeetle>" + BridgeListener.QUEUE,
            "$(PROJECTION_TRANSFERS_EXCHANGE)>$(PROJECTION_TRANSFERS_QUEUE)");
    assertThat(literal(fanout, "WAIT_RABBITMQ_SHARDED_BINDING"))
        .isEqualTo(
            "$(PROJECTION_ENTRIES_EXCHANGE)>$(PROJECTION_ENTRIES_QUEUE_PREFIX)"
                + ">$(PROJECTION_ENTRIES_QUEUE_COUNT)");
    for (String key :
        List.of(
            "PROJECTION_TRANSFERS_EXCHANGE",
            "PROJECTION_TRANSFERS_QUEUE",
            "PROJECTION_ENTRIES_EXCHANGE",
            "PROJECTION_ENTRIES_QUEUE_PREFIX",
            "PROJECTION_ENTRIES_QUEUE_COUNT")) {
      Map<String, Object> ref = map(map(fanout.get(key), "valueFrom"), "configMapKeyRef");
      assertThat(ref).as("amqp-fanout's %s comes from projection-worker's config", key).isNotNull();
      assertThat(ref).containsEntry("name", "projection-worker-config").containsEntry("key", key);
      assertThat(projection).containsKey(key);
    }

    // The CDC job publishes into its exchange; bridge.ledger must be bound to it first.
    Map<String, Map<String, Object>> cdc = waitEnv(workloads.get("tigerbeetle-cdc"));
    assertThat(literal(cdc, "WAIT_RABBITMQ_BINDINGS"))
        .isEqualTo("$(CDC_PUBLISH_EXCHANGE)>" + BridgeListener.QUEUE);
    assertThat(map(map(cdc.get("CDC_PUBLISH_EXCHANGE"), "valueFrom"), "configMapKeyRef"))
        .containsEntry("name", "tigerbeetle-cdc-config");

    // The fixed names amqp-fanout waits for are the ones the topology Job declares.
    String topology = configMaps(docs).get("rabbitmq-topology-script").get("declare-topology.sh");
    assertThat(topology)
        .contains("/api/exchanges/${vhost_encoded}/tigerbeetle")
        .contains("/api/bindings/${vhost_encoded}/e/tigerbeetle/q/" + BridgeListener.QUEUE);
  }

  @Test
  @DisplayName("Every pod that waits for RabbitMQ may reach its management API")
  void rabbitmqWaitsAreAllowedThroughNetworkPolicy() throws Exception {
    List<Map<String, Object>> docs = baseDocuments();
    Map<String, Map<String, Object>> policies = new LinkedHashMap<>();
    for (Map<String, Object> doc : ofKind(docs, "NetworkPolicy")) {
      Map<String, Object> labels = map(map(map(doc, "spec"), "podSelector"), "matchLabels");
      if (labels != null && labels.get("app.kubernetes.io/name") != null) {
        policies.put((String) labels.get("app.kubernetes.io/name"), doc);
      }
    }
    for (var entry : workloads(docs).entrySet()) {
      if (!waitsOf(podSpec(entry.getValue())).contains("wait-for-rabbitmq")) {
        continue;
      }
      Map<String, Object> policy = policies.get(entry.getKey());
      assertThat(policy).as("NetworkPolicy for %s", entry.getKey()).isNotNull();
      assertThat(egressPortsTo(policy, "rabbitmq"))
          .as("%s egress to rabbitmq", entry.getKey())
          .contains(15672);
    }
  }

  @Test
  @DisplayName("The setup Jobs outlast slow datastore formation instead of failing for good")
  void setupJobsOutlastSlowFormation() throws Exception {
    Map<String, Map<String, Object>> workloads = workloads(baseDocuments());
    for (String job : List.of("rabbitmq-topology", "object-store-buckets")) {
      Map<String, Object> spec = map(workloads.get(job), "spec");
      assertThat((Integer) spec.get("backoffLimit")).as("%s backoffLimit", job).isGreaterThan(10);
      // Longer than one full init wait (WAIT_TIMEOUT_SECONDS defaults to 900) with room to retry.
      assertThat((Integer) spec.get("activeDeadlineSeconds"))
          .as("%s activeDeadlineSeconds", job)
          .isGreaterThanOrEqualTo(1800);
    }
    // A transport error (DNS not answering yet) is retried in the pod, not by failing it.
    Map<String, Object> buckets =
        list(podSpec(workloads.get("object-store-buckets")), "containers").get(0);
    @SuppressWarnings("unchecked")
    List<String> command = (List<String>) buckets.get("command");
    assertThat(command.get(command.size() - 1))
        .contains(". /startup-waits/lib.sh")
        .contains("wait_for \"bucket ${bucket}\" create_bucket");
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "prod", "gke", "gke-small"})
  @DisplayName("Every overlay keeps the base's waits, and every key they read resolves")
  void overlaysKeepTheWaits(String overlay) throws Exception {
    List<Map<String, Object>> rendered = renderOverlay(overlay);
    Map<String, Map<String, String>> configMaps = configMaps(rendered);
    assertThat(configMaps)
        .as("%s renders the startup-waits ConfigMap", overlay)
        .containsKey(SCRIPTS_CONFIG_MAP);
    Map<String, Map<String, Object>> renderedWorkloads = workloads(rendered);
    for (var base : workloads(baseDocuments()).entrySet()) {
      Map<String, Object> workload = renderedWorkloads.get(base.getKey());
      assertThat(workload).as("%s renders %s", overlay, base.getKey()).isNotNull();
      Map<String, Object> podSpec = podSpec(workload);
      assertThat(waitsOf(podSpec))
          .as("%s keeps %s's waits", overlay, base.getKey())
          .isEqualTo(waitsOf(podSpec(base.getValue())));
      for (Map<String, Object> init : list(podSpec, "initContainers")) {
        for (Map<String, Object> variable : list(init, "env")) {
          Map<String, Object> ref = map(map(variable, "valueFrom"), "configMapKeyRef");
          if (ref == null) {
            continue;
          }
          assertThat(configMaps.get((String) ref.get("name")))
              .as(
                  "%s: %s/%s reads %s from ConfigMap %s",
                  overlay, base.getKey(), init.get("name"), ref.get("key"), ref.get("name"))
              .containsKey((String) ref.get("key"));
        }
      }
    }
  }

  // ------------------------------------------------------------------ helpers --

  /** Postgres, RabbitMQ, Valkey, the object store and TigerBeetle, as this pod references them. */
  private static Set<String> dependencies(
      String workload, Map<String, Object> podSpec, Map<String, Map<String, String>> configMaps) {
    Set<String> found = new TreeSet<>();
    for (Map<String, Object> container : list(podSpec, "containers")) {
      for (Map<String, Object> variable : list(container, "env")) {
        Map<String, Object> valueFrom = map(variable, "valueFrom");
        Map<String, Object> secret = map(valueFrom, "secretKeyRef");
        if (secret != null) {
          addForSecret(found, (String) secret.get("name"));
        }
        Map<String, Object> configMap = map(valueFrom, "configMapKeyRef");
        if (configMap != null && isLedgerConfig(configMaps.get((String) configMap.get("name")))) {
          found.add("tigerbeetle");
        }
      }
      for (Map<String, Object> source : list(container, "envFrom")) {
        Map<String, Object> secret = map(source, "secretRef");
        if (secret != null) {
          addForSecret(found, (String) secret.get("name"));
        }
        Map<String, Object> configMap = map(source, "configMapRef");
        if (configMap != null && isLedgerConfig(configMaps.get((String) configMap.get("name")))) {
          found.add("tigerbeetle");
        }
      }
    }
    found.removeAll(NOT_A_STARTUP_DEPENDENCY.getOrDefault(workload, Set.of()));
    return found;
  }

  private static void addForSecret(Set<String> found, String secret) {
    switch (secret) {
      case "postgres-app" -> found.add("postgres");
      case "rabbitmq-default-user" -> found.add("rabbitmq");
      case "valkey-credentials" -> found.add("valkey");
      case "object-store-credentials" -> found.add("object-store");
      default -> {}
    }
  }

  private static boolean isLedgerConfig(Map<String, String> data) {
    return data != null
        && (data.containsKey("TIGERBEETLE_ADDRESSES")
            || data.containsKey("CDC_TIGERBEETLE_ADDRESSES"));
  }

  private static Set<String> waitsOf(Map<String, Object> podSpec) {
    Set<String> waits = new TreeSet<>();
    for (Map<String, Object> init : list(podSpec, "initContainers")) {
      String name = (String) init.get("name");
      if (name.startsWith("wait-for-")) {
        waits.add(name);
      }
    }
    return waits;
  }

  /** The env of a workload's wait-for-rabbitmq container, by variable name. */
  private static Map<String, Map<String, Object>> waitEnv(Map<String, Object> workload) {
    for (Map<String, Object> init : list(podSpec(workload), "initContainers")) {
      if ("wait-for-rabbitmq".equals(init.get("name"))) {
        return env(init);
      }
    }
    throw new AssertionError("no wait-for-rabbitmq in " + name(workload));
  }

  private static Map<String, Map<String, Object>> env(Map<String, Object> container) {
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (Map<String, Object> variable : list(container, "env")) {
      byName.put((String) variable.get("name"), variable);
    }
    return byName;
  }

  private static String literal(Map<String, Map<String, Object>> env, String name) {
    assertThat(env).as("env %s", name).containsKey(name);
    return (String) env.get(name).get("value");
  }

  private static Set<String> mountPaths(Map<String, Object> container) {
    Set<String> paths = new TreeSet<>();
    for (Map<String, Object> mount : list(container, "volumeMounts")) {
      paths.add((String) mount.get("mountPath"));
    }
    return paths;
  }

  private static Set<Integer> egressPortsTo(Map<String, Object> policy, String app) {
    Set<Integer> ports = new TreeSet<>();
    for (Map<String, Object> rule : list(map(policy, "spec"), "egress")) {
      boolean toApp = false;
      for (Map<String, Object> peer : list(rule, "to")) {
        Map<String, Object> labels = map(map(peer, "podSelector"), "matchLabels");
        if (labels != null && app.equals(labels.get("app.kubernetes.io/name"))) {
          toApp = true;
        }
      }
      if (toApp) {
        for (Map<String, Object> port : list(rule, "ports")) {
          ports.add((Integer) port.get("port"));
        }
      }
    }
    return ports;
  }

  private static Map<String, String> scripts(List<Map<String, Object>> docs) {
    Map<String, String> data = configMaps(docs).get(SCRIPTS_CONFIG_MAP);
    assertThat(data).as("ConfigMap %s", SCRIPTS_CONFIG_MAP).isNotNull();
    return data;
  }

  private static Map<String, Map<String, Object>> workloads(List<Map<String, Object>> docs) {
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (Map<String, Object> doc : docs) {
      if ("Deployment".equals(doc.get("kind")) || "Job".equals(doc.get("kind"))) {
        byName.put(name(doc), doc);
      }
    }
    return byName;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, String>> configMaps(List<Map<String, Object>> docs) {
    Map<String, Map<String, String>> byName = new LinkedHashMap<>();
    for (Map<String, Object> doc : ofKind(docs, "ConfigMap")) {
      Object data = doc.get("data");
      byName.put(name(doc), data == null ? Map.of() : (Map<String, String>) data);
    }
    return byName;
  }

  private static List<Map<String, Object>> baseDocuments() throws Exception {
    List<Map<String, Object>> docs = new ArrayList<>();
    try (Stream<Path> files = Files.walk(BASE)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
        try (InputStream in = Files.newInputStream(file)) {
          for (Object doc : new Yaml().loadAll(in)) {
            if (doc instanceof Map<?, ?> m && m.get("kind") != null) {
              @SuppressWarnings("unchecked")
              Map<String, Object> typed = (Map<String, Object>) m;
              docs.add(typed);
            }
          }
        }
      }
    }
    return docs;
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

  private static Map<String, Object> podSpec(Map<String, Object> workload) {
    return map(map(map(workload, "spec"), "template"), "spec");
  }

  private static String name(Map<String, Object> doc) {
    return (String) map(doc, "metadata").get("name");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object parent, String key) {
    if (!(parent instanceof Map<?, ?> m)) {
      return null;
    }
    return (Map<String, Object>) m.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
    Object value = parent == null ? null : parent.get(key);
    return value == null ? List.of() : (List<Map<String, Object>>) value;
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
