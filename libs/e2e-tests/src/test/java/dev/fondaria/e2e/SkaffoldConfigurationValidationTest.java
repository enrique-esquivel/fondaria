package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 12.6 acceptance validations:
 *
 * <p>1. skaffold.yaml exists at repository root and parses as valid Config (v4beta11).
 *
 * <p>2. Jib builds configured for all 8 microservices (no Docker daemon needed).
 *
 * <p>3. File sync is configured on Jib artifacts for fast iteration.
 *
 * <p>4. Manifest deployment targets infrastructure/k8s/overlays/local overlay.
 *
 * <p>5. Port forwarding configured for APIs and infrastructure services.
 */
class SkaffoldConfigurationValidationTest {

  private static final Set<String> EXPECTED_SERVICES =
      Set.of(
          "contribution-api",
          "balance-api",
          "valuation-api",
          "allotment-job",
          "amqp-fanout",
          "projection-worker",
          "eod-job",
          "statement-worker");

  @Test
  @DisplayName("skaffold.yaml exists at root and has valid metadata and apiVersion")
  void skaffoldYamlExistsAndHasValidMetadata() throws Exception {
    Path skaffoldPath = Path.of(RepositoryRoot.path("skaffold.yaml"));
    assertThat(skaffoldPath).isRegularFile();

    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(skaffoldPath.toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);

      assertThat(doc.get("apiVersion")).isEqualTo("skaffold/v4beta11");
      assertThat(doc.get("kind")).isEqualTo("Config");

      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
      assertThat(metadata.get("name")).isEqualTo("fondaria");
    }
  }

  @Test
  @DisplayName("Jib build artifacts configured for all 8 microservices with file sync")
  void jibBuildArtifactsConfiguredForAllServices() throws Exception {
    Path skaffoldPath = Path.of(RepositoryRoot.path("skaffold.yaml"));
    Yaml yaml = new Yaml();

    try (InputStream in = new FileInputStream(skaffoldPath.toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);

      @SuppressWarnings("unchecked")
      Map<String, Object> build = (Map<String, Object>) doc.get("build");
      assertThat(build).isNotNull();

      @SuppressWarnings("unchecked")
      Map<String, Object> local = (Map<String, Object>) build.get("local");
      assertThat(local).isNotNull();
      assertThat(local.get("push")).isEqualTo(false);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> artifacts = (List<Map<String, Object>>) build.get("artifacts");
      assertThat(artifacts).hasSize(8);

      for (Map<String, Object> artifact : artifacts) {
        String image = (String) artifact.get("image");
        assertThat(image).startsWith("fondaria/");
        String serviceName = image.substring("fondaria/".length());
        assertThat(EXPECTED_SERVICES).contains(serviceName);

        @SuppressWarnings("unchecked")
        Map<String, Object> jib = (Map<String, Object>) artifact.get("jib");
        assertThat(jib).as("Artifact %s must configure Jib builder", image).isNotNull();
        assertThat(jib.get("project")).isEqualTo("services/" + serviceName);

        @SuppressWarnings("unchecked")
        Map<String, Object> sync = (Map<String, Object>) artifact.get("sync");
        assertThat(sync)
            .as("Artifact %s must configure sync for fast iteration", image)
            .isNotNull();
        assertThat(sync.get("auto")).isEqualTo(true);
      }
    }
  }

  @Test
  @DisplayName("Manifests point to local Kustomize overlay")
  void manifestsTargetLocalKustomizeOverlay() throws Exception {
    Path skaffoldPath = Path.of(RepositoryRoot.path("skaffold.yaml"));
    Yaml yaml = new Yaml();

    try (InputStream in = new FileInputStream(skaffoldPath.toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);

      @SuppressWarnings("unchecked")
      Map<String, Object> manifests = (Map<String, Object>) doc.get("manifests");
      assertThat(manifests).isNotNull();

      @SuppressWarnings("unchecked")
      Map<String, Object> kustomize = (Map<String, Object>) manifests.get("kustomize");
      assertThat(kustomize).isNotNull();

      @SuppressWarnings("unchecked")
      List<String> paths = (List<String>) kustomize.get("paths");
      assertThat(paths).containsExactly("infrastructure/k8s/overlays/local");
    }
  }

  @Test
  @DisplayName("Port forwarding configured for APIs and infrastructure services")
  void portForwardingConfiguredForApisAndDataStores() throws Exception {
    Path skaffoldPath = Path.of(RepositoryRoot.path("skaffold.yaml"));
    Yaml yaml = new Yaml();

    try (InputStream in = new FileInputStream(skaffoldPath.toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> portForwards = (List<Map<String, Object>>) doc.get("portForward");
      assertThat(portForwards).isNotEmpty();

      Map<String, Integer> expectedPorts =
          Map.of(
              "contribution-api", 30080,
              "balance-api", 30081,
              "valuation-api", 30082,
              "tigerbeetle", 3000,
              "valkey", 6379);

      for (Map.Entry<String, Integer> entry : expectedPorts.entrySet()) {
        boolean found =
            portForwards.stream()
                .anyMatch(
                    pf ->
                        entry.getKey().equals(pf.get("resourceName"))
                            && entry.getValue().equals(pf.get("localPort")));
        assertThat(found)
            .as(
                "Port forward for %s on port %d must be configured",
                entry.getKey(), entry.getValue())
            .isTrue();
      }
    }
  }
}
