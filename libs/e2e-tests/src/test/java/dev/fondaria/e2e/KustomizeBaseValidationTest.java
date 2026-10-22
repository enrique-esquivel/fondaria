package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class KustomizeBaseValidationTest {

  private static final List<String> SERVICES =
      List.of(
          "contribution-api",
          "allotment-job",
          "amqp-fanout",
          "projection-worker",
          "balance-api",
          "eod-job",
          "valuation-api",
          "statement-worker",
          "tigerbeetle-cdc");

  private static final List<String> REQUIRED_FILES =
      List.of(
          "deployment.yaml",
          "service.yaml",
          "serviceaccount.yaml",
          "configmap.yaml",
          "pdb.yaml",
          "kustomization.yaml");

  /**
   * Everything that links the TigerBeetle client runs with seccomp {@code Unconfined}: io_uring is
   * TigerBeetle's I/O engine, in the replica and in the client library alike, and the runtime's
   * default profile blocks it (the replica exits with "io_uring is not available ...
   * PermissionDenied"; a service with "tb_client_context: failed to initialize IO:
   * PermissionDenied"). Recorded here by name so a new workload cannot inherit the exception
   * silently; the StatefulSet side is asserted by {@code TigerBeetleStatefulSetValidationTest}.
   */
  private static final Set<String> IO_URING_WORKLOADS =
      Set.of(
          "tigerbeetle-cdc",
          "contribution-api",
          "balance-api",
          "valuation-api",
          "allotment-job",
          "eod-job");

  private static final Pattern PLAINTEXT_PASSWORD_PATTERN =
      Pattern.compile("(?i)(password|secret|key):\\s*[\"']?[a-zA-Z0-9_-]{5,}[\"']?");

  @Test
  @DisplayName("All required base files exist for every service workload")
  void allRequiredBaseFilesExist() {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    assertThat(basePath.resolve("kustomization.yaml")).exists();

    for (String service : SERVICES) {
      Path serviceDir = basePath.resolve(service);
      assertThat(serviceDir).as("Service directory for %s must exist", service).isDirectory();

      for (String requiredFile : REQUIRED_FILES) {
        assertThat(serviceDir.resolve(requiredFile))
            .as("Required manifest %s must exist in %s", requiredFile, service)
            .isRegularFile();
      }
    }
  }

  @Test
  @DisplayName("kustomize build succeeds for every service and the root base")
  void kustomizeBuildSucceeds() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    String kubectlPath = findExecutable("kubectl");

    if (kubectlPath != null) {
      // Build root base
      Process rootProcess =
          new ProcessBuilder(kubectlPath, "kustomize", basePath.toString()).start();
      String rootOut =
          new String(rootProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int rootExit = rootProcess.waitFor();
      assertThat(rootExit).as("kubectl kustomize on root base must succeed").isZero();
      assertThat(rootOut).contains("kind: Deployment");

      // Build each service individually
      for (String service : SERVICES) {
        Process serviceProcess =
            new ProcessBuilder(kubectlPath, "kustomize", basePath.resolve(service).toString())
                .start();
        String serviceOut =
            new String(serviceProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int serviceExit = serviceProcess.waitFor();
        assertThat(serviceExit).as("kubectl kustomize on %s must succeed", service).isZero();
        assertThat(serviceOut).contains("kind: Deployment");
      }
    }

    // Also assert all YAML files can be cleanly parsed by YAML parser
    Yaml yaml = new Yaml();
    for (String service : SERVICES) {
      Path serviceDir = basePath.resolve(service);
      for (String file : REQUIRED_FILES) {
        try (InputStream in = new FileInputStream(serviceDir.resolve(file).toFile())) {
          Iterable<Object> documents = yaml.loadAll(in);
          assertThat(documents).as("Parsed documents in %s/%s", service, file).isNotEmpty();
        }
      }
    }
  }

  @Test
  @DisplayName("Security context rules are enforced on every pod and container")
  void securityContextRulesEnforced() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    for (String service : SERVICES) {
      File deploymentFile = basePath.resolve(service).resolve("deployment.yaml").toFile();
      try (InputStream in = new FileInputStream(deploymentFile)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        validateSecurityContext(service, doc);
      }
    }
  }

  @Test
  @DisplayName("Resource requests and limits are specified on every container with bounded memory")
  void resourceRequestsAndLimitsEnforced() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    for (String service : SERVICES) {
      File deploymentFile = basePath.resolve(service).resolve("deployment.yaml").toFile();
      try (InputStream in = new FileInputStream(deploymentFile)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
          String containerName = (String) container.get("name");
          @SuppressWarnings("unchecked")
          Map<String, Object> resources = (Map<String, Object>) container.get("resources");
          assertThat(resources)
              .as("Container %s in %s must specify resources", containerName, service)
              .isNotNull();

          @SuppressWarnings("unchecked")
          Map<String, Object> requests = (Map<String, Object>) resources.get("requests");
          assertThat(requests).as("Requests in %s/%s", service, containerName).isNotNull();
          assertThat(requests.get("cpu")).isNotNull();
          assertThat(requests.get("memory")).isNotNull();

          @SuppressWarnings("unchecked")
          Map<String, Object> limits = (Map<String, Object>) resources.get("limits");
          assertThat(limits).as("Limits in %s/%s", service, containerName).isNotNull();
          assertThat(limits.get("cpu")).isNotNull();
          assertThat(limits.get("memory")).isNotNull();
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Probes (startup, liveness, readiness) are defined with documented justification comments")
  void probesAreDefinedAndJustified() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    for (String service : SERVICES) {
      Path deploymentPath = basePath.resolve(service).resolve("deployment.yaml");
      String content = Files.readString(deploymentPath);

      // Verify comments exist
      assertThat(content)
          .as("Deployment %s must contain Startup probe comment", service)
          .containsIgnoringCase("Startup probe");
      assertThat(content)
          .as("Deployment %s must contain Liveness probe comment", service)
          .containsIgnoringCase("Liveness probe");
      assertThat(content)
          .as("Deployment %s must contain Readiness probe comment", service)
          .containsIgnoringCase("Readiness probe");

      try (InputStream in = new FileInputStream(deploymentPath.toFile())) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
          String containerName = (String) container.get("name");
          assertThat(container.get("startupProbe"))
              .as("Startup probe in %s/%s", service, containerName)
              .isNotNull();
          assertThat(container.get("livenessProbe"))
              .as("Liveness probe in %s/%s", service, containerName)
              .isNotNull();
          assertThat(container.get("readinessProbe"))
              .as("Readiness probe in %s/%s", service, containerName)
              .isNotNull();
        }
      }
    }
  }

  @Test
  @DisplayName("Topology spread constraints across zones are defined on all workloads")
  void topologySpreadConstraintsDefined() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    for (String service : SERVICES) {
      File deploymentFile = basePath.resolve(service).resolve("deployment.yaml").toFile();
      try (InputStream in = new FileInputStream(deploymentFile)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> constraints =
            (List<Map<String, Object>>) podSpec.get("topologySpreadConstraints");
        assertThat(constraints).as("topologySpreadConstraints in %s", service).isNotEmpty();

        boolean hasZoneSpread =
            constraints.stream()
                .anyMatch(c -> "topology.kubernetes.io/zone".equals(c.get("topologyKey")));
        assertThat(hasZoneSpread)
            .as("Must spread across topology.kubernetes.io/zone in %s", service)
            .isTrue();
      }
    }
  }

  @Test
  @DisplayName(
      "Platform images are the Jib-built fondaria/<service>:<version>; every third-party image is pinned by digest")
  void imagesArePinned() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    // The platform's own images have no registry yet: Jib builds them locally (skaffold) and the
    // GitOps task promotes them by tag. They must be the service's own image, never :latest.
    for (String service : SERVICES) {
      File deploymentFile = basePath.resolve(service).resolve("deployment.yaml").toFile();
      try (InputStream in = new FileInputStream(deploymentFile)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
          String image = (String) container.get("image");
          if ("tigerbeetle-cdc".equals(service)) {
            assertThat(image).as("the CDC job runs TigerBeetle's own image").contains("@sha256:");
          } else {
            assertThat(image)
                .as("Container image in %s must be this service's own Jib image", service)
                .startsWith("fondaria/" + service + ":")
                .doesNotEndWith(":latest");
          }
        }
      }
    }

    // Everything that is not ours: by digest, so a tag cannot move under the platform.
    Pattern imageLine =
        Pattern.compile("^\\s*(?:-\\s*)?image(?:Name)?:\\s*[\"']?([^\"'\\s]+)", Pattern.MULTILINE);
    try (var stream = Files.walk(basePath)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        if (!file.toString().endsWith(".yaml")) {
          continue;
        }
        var matcher = imageLine.matcher(Files.readString(file));
        while (matcher.find()) {
          String image = matcher.group(1);
          if (image.startsWith("fondaria/")) {
            continue;
          }
          assertThat(image)
              .as("Third-party image %s in %s must be pinned by digest", image, file)
              .contains("@sha256:");
        }
      }
    }
  }

  @Test
  @DisplayName(
      "No plaintext credentials or Secret resources in manifests (ExternalSecret references only)")
  void noPlaintextCredentialsOrSecretResources() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));

    try (var stream = Files.walk(basePath)) {
      List<Path> yamlFiles =
          stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".yaml")).toList();

      for (Path file : yamlFiles) {
        String content = Files.readString(file);
        assertThat(content)
            .as("Manifest %s must NOT declare kind: Secret (ExternalSecret only)", file)
            .doesNotContain("kind: Secret\n")
            .doesNotContain("kind: Secret\r\n");

        if (file.toString().contains("configmap.yaml")) {
          // Assert no password values in configmaps. A shell script copied into a ConfigMap
          // legitimately says `${CDC_AMQP_PASSWORD:?...}` -- a reference, not a value -- so
          // the check is for a key followed by a literal, not for the word.
          assertThat(content)
              .as("ConfigMap %s must not hold plaintext passwords", file)
              .doesNotContainPattern("(?i)(password|secret)\\s*:\\s*[\"']?[A-Za-z0-9_-]{4,}");
        }
      }
    }
  }

  @Test
  @DisplayName("Security policy check fails on a deliberate security context violation")
  void policyFailsOnDeliberateViolation() {
    // 1. Deliberate violation: running as root
    Map<String, Object> rootViolation =
        Map.of(
            "metadata", Map.of("name", "insecure-root"),
            "spec",
                Map.of(
                    "template",
                    Map.of(
                        "spec",
                        Map.of(
                            "securityContext",
                                Map.of(
                                    "runAsNonRoot",
                                    false,
                                    "seccompProfile",
                                    Map.of("type", "RuntimeDefault")),
                            "containers", List.of()))));

    assertThatThrownBy(() -> validateSecurityContext("root-service", rootViolation))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("runAsNonRoot");

    // 2. Deliberate violation: writable root filesystem
    Map<String, Object> writableViolation =
        Map.of(
            "metadata", Map.of("name", "insecure-fs"),
            "spec",
                Map.of(
                    "template",
                    Map.of(
                        "spec",
                        Map.of(
                            "securityContext",
                            Map.of(
                                "runAsNonRoot",
                                true,
                                "seccompProfile",
                                Map.of("type", "RuntimeDefault")),
                            "containers",
                            List.of(
                                Map.of(
                                    "name",
                                    "app",
                                    "securityContext",
                                    Map.of(
                                        "readOnlyRootFilesystem", false,
                                        "allowPrivilegeEscalation", false,
                                        "capabilities", Map.of("drop", List.of("ALL")))))))));

    assertThatThrownBy(() -> validateSecurityContext("fs-service", writableViolation))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("readOnlyRootFilesystem");

    // 3. Deliberate violation: missing drop ALL capabilities
    Map<String, Object> capsViolation =
        Map.of(
            "metadata", Map.of("name", "insecure-caps"),
            "spec",
                Map.of(
                    "template",
                    Map.of(
                        "spec",
                        Map.of(
                            "securityContext",
                            Map.of(
                                "runAsNonRoot",
                                true,
                                "seccompProfile",
                                Map.of("type", "RuntimeDefault")),
                            "containers",
                            List.of(
                                Map.of(
                                    "name",
                                    "app",
                                    "securityContext",
                                    Map.of(
                                        "readOnlyRootFilesystem", true,
                                        "allowPrivilegeEscalation", false,
                                        "capabilities",
                                            Map.of("drop", List.of("NET_BIND_SERVICE")))))))));

    assertThatThrownBy(() -> validateSecurityContext("caps-service", capsViolation))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("capabilities.drop");
  }

  @Test
  @DisplayName(
      "All required application environment variables without default values are satisfied by ConfigMap or ExternalSecret")
  void applicationEnvironmentVariablesAreSatisfied() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Yaml yaml = new Yaml();

    // Matches non-defaulted placeholders like ${VAR_NAME:} or ${VAR_NAME}
    Pattern requiredVarPattern = Pattern.compile("\\$\\{([A-Z0-9_]+):?\\}");

    for (String service : SERVICES) {
      if ("tigerbeetle-cdc".equals(service)) {
        continue;
      }

      Path appYamlPath =
          Path.of(
              RepositoryRoot.path("services/" + service + "/src/main/resources/application.yaml"));
      assertThat(appYamlPath).exists();

      String appYamlContent = Files.readString(appYamlPath, StandardCharsets.UTF_8);
      var matcher = requiredVarPattern.matcher(appYamlContent);
      Set<String> requiredVars = new HashSet<>();
      while (matcher.find()) {
        String varName = matcher.group(1);
        // Exclude variables that are optional or intentionally defaulted to empty
        if (!"CALENDAR_HOLIDAYS".equals(varName)
            && !"CDC_TIMESTAMP_LAST".equals(varName)
            && !"TRACE_CORRELATION_REDIS_URL".equals(varName)
            && !"TRACE_CORRELATION_REDIS_PASSWORD".equals(varName)) {
          requiredVars.add(varName);
        }
      }

      // Read service ConfigMap
      File cmFile = basePath.resolve(service).resolve("configmap.yaml").toFile();
      Map<String, Object> cmDoc;
      try (InputStream in = new FileInputStream(cmFile)) {
        cmDoc = yaml.load(in);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> cmData = (Map<String, Object>) cmDoc.getOrDefault("data", Map.of());
      Set<String> providedVars = new HashSet<>(cmData.keySet());

      // Read the Deployment's explicit env entries -- credentials arrive as secretKeyRefs into
      // the Secrets the operators generate, not from a per-service ExternalSecret.
      File deploymentFile = basePath.resolve(service).resolve("deployment.yaml").toFile();
      Map<String, Object> deploymentDoc;
      try (InputStream in = new FileInputStream(deploymentFile)) {
        deploymentDoc = yaml.load(in);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> deploymentSpec = (Map<String, Object>) deploymentDoc.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> deploymentTemplate = (Map<String, Object>) deploymentSpec.get("template");
      @SuppressWarnings("unchecked")
      Map<String, Object> deploymentPodSpec = (Map<String, Object>) deploymentTemplate.get("spec");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> deploymentContainers =
          (List<Map<String, Object>>) deploymentPodSpec.get("containers");
      for (Map<String, Object> container : deploymentContainers) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> env =
            (List<Map<String, Object>>) container.getOrDefault("env", List.of());
        for (Map<String, Object> entry : env) {
          providedVars.add((String) entry.get("name"));
        }
      }

      // Assert all requiredVars are provided
      for (String requiredVar : requiredVars) {
        assertThat(providedVars)
            .as(
                "Required environment variable %s in service %s must be provided by the ConfigMap or a Deployment env entry",
                requiredVar, service)
            .contains(requiredVar);
      }
    }
  }

  private static final List<String> OBSERVABILITY_WORKLOADS =
      List.of("otel-collector", "prometheus", "tempo", "loki", "grafana");

  @Test
  @DisplayName("All required observability manifests exist and have strict structure")
  void allRequiredObservabilityManifestsExist() {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    assertThat(obsBase.resolve("kustomization.yaml")).isRegularFile();
    assertThat(obsBase.resolve("networkpolicies/observability-network-policies.yaml"))
        .isRegularFile();
    assertThat(obsBase.resolve("networkpolicies/kustomization.yaml")).isRegularFile();

    for (String workload : OBSERVABILITY_WORKLOADS) {
      Path dir = obsBase.resolve(workload);
      assertThat(dir).isDirectory();
      assertThat(dir.resolve("service.yaml")).isRegularFile();
      assertThat(dir.resolve("serviceaccount.yaml")).isRegularFile();
      assertThat(dir.resolve("configmap.yaml")).isRegularFile();
      assertThat(dir.resolve("pdb.yaml")).isRegularFile();
      assertThat(dir.resolve("kustomization.yaml")).isRegularFile();

      if ("otel-collector".equals(workload) || "grafana".equals(workload)) {
        assertThat(dir.resolve("deployment.yaml")).isRegularFile();
      } else {
        assertThat(dir.resolve("statefulset.yaml")).isRegularFile();
      }

      if ("grafana".equals(workload)) {
        assertThat(dir.resolve("externalsecret.yaml")).isRegularFile();
      }
    }
  }

  @Test
  @DisplayName(
      "Observability workloads enforce non-root, read-only rootfs and dropped capabilities")
  void observabilitySecurityContextRulesEnforced() throws Exception {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    Yaml yaml = new Yaml();

    for (String workload : OBSERVABILITY_WORKLOADS) {
      String manifest =
          ("otel-collector".equals(workload) || "grafana".equals(workload))
              ? "deployment.yaml"
              : "statefulset.yaml";
      File file = obsBase.resolve(workload).resolve(manifest).toFile();
      try (InputStream in = new FileInputStream(file)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        validateSecurityContext(workload, doc);
      }
    }
  }

  @Test
  @DisplayName("Observability workloads specify explicit CPU/memory requests and limits")
  void observabilityResourceRequestsAndLimitsEnforced() throws Exception {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    Yaml yaml = new Yaml();

    for (String workload : OBSERVABILITY_WORKLOADS) {
      String manifest =
          ("otel-collector".equals(workload) || "grafana".equals(workload))
              ? "deployment.yaml"
              : "statefulset.yaml";
      File file = obsBase.resolve(workload).resolve(manifest).toFile();
      try (InputStream in = new FileInputStream(file)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
          @SuppressWarnings("unchecked")
          Map<String, Object> resources = (Map<String, Object>) container.get("resources");
          assertThat(resources).isNotNull();
          @SuppressWarnings("unchecked")
          Map<String, Object> requests = (Map<String, Object>) resources.get("requests");
          assertThat(requests).isNotNull();
          assertThat(requests.get("cpu")).isNotNull();
          assertThat(requests.get("memory")).isNotNull();

          @SuppressWarnings("unchecked")
          Map<String, Object> limits = (Map<String, Object>) resources.get("limits");
          assertThat(limits).isNotNull();
          assertThat(limits.get("cpu")).isNotNull();
          assertThat(limits.get("memory")).isNotNull();

          // Pinned digest check
          String image = (String) container.get("image");
          assertThat(image)
              .as("Image in %s must be pinned by sha256 digest", workload)
              .contains("@sha256:");
        }
      }
    }
  }

  @Test
  @DisplayName("Observability workloads define documented justification comments for probes")
  void observabilityProbesAreDefinedAndJustified() throws Exception {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    Yaml yaml = new Yaml();

    for (String workload : OBSERVABILITY_WORKLOADS) {
      String manifest =
          ("otel-collector".equals(workload) || "grafana".equals(workload))
              ? "deployment.yaml"
              : "statefulset.yaml";
      Path path = obsBase.resolve(workload).resolve(manifest);
      String content = Files.readString(path);

      assertThat(content)
          .as("Workload %s must contain Startup probe comment", workload)
          .containsIgnoringCase("Startup probe");
      assertThat(content)
          .as("Workload %s must contain Liveness probe comment", workload)
          .containsIgnoringCase("Liveness probe");
      assertThat(content)
          .as("Workload %s must contain Readiness probe comment", workload)
          .containsIgnoringCase("Readiness probe");

      try (InputStream in = new FileInputStream(path.toFile())) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
          assertThat(container.get("startupProbe")).isNotNull();
          assertThat(container.get("livenessProbe")).isNotNull();
          assertThat(container.get("readinessProbe")).isNotNull();
        }
      }
    }
  }

  @Test
  @DisplayName("Observability ConfigMaps match source files in infrastructure/observability/**")
  void observabilityConfigMapsMatchSourceFiles() throws Exception {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    Path obsSource = Path.of(RepositoryRoot.path("infrastructure/observability"));
    Yaml yaml = new Yaml();

    // 1. otel-collector
    try (InputStream in =
        new FileInputStream(obsBase.resolve("otel-collector/configmap.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);
      @SuppressWarnings("unchecked")
      Map<String, String> data = (Map<String, String>) doc.get("data");
      assertThat(data.get("config.yaml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("otel-collector/config.yaml")).trim());
    }

    // 2. prometheus
    try (InputStream in =
        new FileInputStream(obsBase.resolve("prometheus/configmap.yaml").toFile())) {
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object d : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) d;
        docs.add(m);
      }
      @SuppressWarnings("unchecked")
      Map<String, String> promData = (Map<String, String>) docs.get(0).get("data");
      assertThat(promData.get("prometheus.yml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("prometheus/prometheus.yml")).trim());

      @SuppressWarnings("unchecked")
      Map<String, String> rulesData = (Map<String, String>) docs.get(1).get("data");
      assertThat(rulesData.get("eod-close.rules.yml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("alerts/eod-close.rules.yml")).trim());
      assertThat(rulesData.get("financial-control.rules.yml").trim())
          .isEqualTo(
              Files.readString(obsSource.resolve("alerts/financial-control.rules.yml")).trim());
      assertThat(rulesData.get("edge-security.rules.yml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("alerts/edge-security.rules.yml")).trim());
    }

    // 3. tempo
    try (InputStream in = new FileInputStream(obsBase.resolve("tempo/configmap.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);
      @SuppressWarnings("unchecked")
      Map<String, String> data = (Map<String, String>) doc.get("data");
      assertThat(data.get("tempo.yaml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("tempo/tempo.yaml")).trim());
    }

    // 4. loki
    try (InputStream in = new FileInputStream(obsBase.resolve("loki/configmap.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);
      @SuppressWarnings("unchecked")
      Map<String, String> data = (Map<String, String>) doc.get("data");
      assertThat(data.get("loki-config.yaml").trim())
          .isEqualTo(Files.readString(obsSource.resolve("loki/loki-config.yaml")).trim());
    }

    // 5. grafana
    try (InputStream in = new FileInputStream(obsBase.resolve("grafana/configmap.yaml").toFile())) {
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object d : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) d;
        docs.add(m);
      }
      @SuppressWarnings("unchecked")
      Map<String, String> dsData = (Map<String, String>) docs.get(0).get("data");
      assertThat(dsData.get("datasources.yml").trim())
          .isEqualTo(
              Files.readString(
                      obsSource.resolve("grafana/provisioning/datasources/datasources.yml"))
                  .trim());

      @SuppressWarnings("unchecked")
      Map<String, String> dashProvData = (Map<String, String>) docs.get(1).get("data");
      assertThat(dashProvData.get("dashboards.yml").trim())
          .isEqualTo(
              Files.readString(obsSource.resolve("grafana/provisioning/dashboards/dashboards.yml"))
                  .trim());

      @SuppressWarnings("unchecked")
      Map<String, String> dashData = (Map<String, String>) docs.get(2).get("data");
      assertThat(dashData.get("collector-health.json").trim())
          .isEqualTo(
              Files.readString(obsSource.resolve("dashboards/collector-health.json")).trim());
      assertThat(dashData.get("financial-control.json").trim())
          .isEqualTo(
              Files.readString(obsSource.resolve("dashboards/financial-control.json")).trim());
    }
  }

  @Test
  @DisplayName(
      "The CDC runner and the RabbitMQ topology script are verbatim copies of their compose originals")
  void bootstrapScriptsMatchComposeOriginals() throws Exception {
    Path k8sBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Path compose = Path.of(RepositoryRoot.path("infrastructure/compose"));
    Yaml yaml = new Yaml();

    try (InputStream in =
        new FileInputStream(k8sBase.resolve("tigerbeetle-cdc/configmap.yaml").toFile())) {
      Map<String, String> scripts = null;
      for (Object d : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) d;
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
        if ("tigerbeetle-cdc-script".equals(metadata.get("name"))) {
          @SuppressWarnings("unchecked")
          Map<String, String> data = (Map<String, String>) doc.get("data");
          scripts = data;
        }
      }
      assertThat(scripts).as("tigerbeetle-cdc-script ConfigMap").isNotNull();
      assertThat(scripts.get("run.sh").trim())
          .as("the k8s CDC runner must be compose's cdc/run.sh (it resolves hostnames)")
          .isEqualTo(Files.readString(compose.resolve("cdc/run.sh")).trim());
    }

    try (InputStream in =
        new FileInputStream(k8sBase.resolve("rabbitmq/topology-job.yaml").toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> configMap = (Map<String, Object>) yaml.loadAll(in).iterator().next();
      @SuppressWarnings("unchecked")
      Map<String, String> data = (Map<String, String>) configMap.get("data");
      assertThat(data.get("declare-topology.sh").trim())
          .isEqualTo(Files.readString(compose.resolve("rabbitmq/declare-topology.sh")).trim());
    }
  }

  @Test
  @DisplayName("Grafana credentials use ExternalSecret and no plaintext secrets exist")
  void noPlaintextSecretsInObservability() throws Exception {
    Path obsBase = Path.of(RepositoryRoot.path("infrastructure/k8s/base/observability"));
    try (var stream = Files.walk(obsBase)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        if (file.toString().endsWith(".yaml")) {
          String content = Files.readString(file);
          assertThat(content)
              .as("File %s must not contain 'kind: Secret'", file)
              .doesNotContain("kind: Secret\n")
              .doesNotContain("kind: Secret\r\n");
        }
      }
    }

    Path grafanaSecret = obsBase.resolve("grafana/externalsecret.yaml");
    assertThat(grafanaSecret).isRegularFile();
    String secretContent = Files.readString(grafanaSecret);
    assertThat(secretContent).contains("kind: ExternalSecret");
    assertThat(secretContent).contains("name: grafana-admin-secret");
    assertThat(secretContent).contains("key: fondaria/grafana");
  }

  private void validateSecurityContext(String service, Map<String, Object> doc) {
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
    @SuppressWarnings("unchecked")
    Map<String, Object> template = (Map<String, Object>) spec.get("template");
    @SuppressWarnings("unchecked")
    Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");

    // Pod level
    @SuppressWarnings("unchecked")
    Map<String, Object> podSec = (Map<String, Object>) podSpec.get("securityContext");
    assertThat(podSec).as("Pod securityContext in %s", service).isNotNull();
    assertThat(podSec.get("runAsNonRoot")).as("runAsNonRoot in %s", service).isEqualTo(true);

    @SuppressWarnings("unchecked")
    Map<String, Object> seccomp = (Map<String, Object>) podSec.get("seccompProfile");
    assertThat(seccomp).as("seccompProfile in %s", service).isNotNull();
    assertThat(seccomp.get("type"))
        .as("seccompProfile.type in %s", service)
        .isEqualTo(IO_URING_WORKLOADS.contains(service) ? "Unconfined" : "RuntimeDefault");

    // Container level
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
    for (Map<String, Object> container : containers) {
      String containerName = (String) container.get("name");
      @SuppressWarnings("unchecked")
      Map<String, Object> containerSec = (Map<String, Object>) container.get("securityContext");
      assertThat(containerSec)
          .as("Container securityContext in %s/%s", service, containerName)
          .isNotNull();

      assertThat(containerSec.get("readOnlyRootFilesystem"))
          .as("readOnlyRootFilesystem in %s/%s", service, containerName)
          .isEqualTo(true);
      assertThat(containerSec.get("allowPrivilegeEscalation"))
          .as("allowPrivilegeEscalation in %s/%s", service, containerName)
          .isEqualTo(false);

      @SuppressWarnings("unchecked")
      Map<String, Object> capabilities = (Map<String, Object>) containerSec.get("capabilities");
      assertThat(capabilities).as("capabilities in %s/%s", service, containerName).isNotNull();
      @SuppressWarnings("unchecked")
      List<String> drop = (List<String>) capabilities.get("drop");
      assertThat(drop).as("capabilities.drop in %s/%s", service, containerName).contains("ALL");
    }
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
    // Also check standard /opt/homebrew/bin or /usr/local/bin if not in PATH
    for (String standardDir : List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")) {
      File file = new File(standardDir, name);
      if (file.canExecute()) {
        return file.getAbsolutePath();
      }
    }
    return null;
  }
}
