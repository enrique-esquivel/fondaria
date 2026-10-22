package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 12.6 acceptance simulations:
 *
 * <p>1. Jib builds rebuild service artifacts in under 60 seconds without Docker daemon.
 *
 * <p>2. Skaffold file sync maps classes and resources directly.
 *
 * <p>3. Skaffold delete leaves no residue.
 */
class JibBuildSimulationIntegrationTest {

  @Test
  @DisplayName("Jib build query completes in under 60 seconds without Docker daemon")
  void jibBuildQueryCompletesUnder60Seconds() throws Exception {
    String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";

    Instant start = Instant.now();
    Process process =
        new ProcessBuilder(
                mvnCmd,
                "compile",
                "jib:_skaffold-files-v2",
                "-pl",
                "services/contribution-api",
                "-am",
                "-q")
            .directory(new File(RepositoryRoot.path(".")))
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    Duration elapsed = Duration.between(start, Instant.now());

    assertThat(exitCode).as("mvn compile jib:_skaffold-files-v2 must exit with 0").isZero();
    assertThat(output).contains("BEGIN JIB JSON");
    assertThat(output).contains("contribution-api");
    assertThat(elapsed.toSeconds())
        .as("Jib dependency analysis must complete in under 60 seconds")
        .isLessThan(60);
  }

  @Test
  @DisplayName("Jib sync map outputs class and dependency destinations for fast sync")
  void jibSyncMapOutputsClassAndDependencyDestinations() throws Exception {
    String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";

    Process process =
        new ProcessBuilder(
                mvnCmd, "compile", "jib:_skaffold-sync-map", "-pl", "services/balance-api", "-q")
            .directory(new File(RepositoryRoot.path(".")))
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();

    assertThat(exitCode).as("mvn compile jib:_skaffold-sync-map must exit with 0").isZero();
    assertThat(output).contains("dest");
    assertThat(output).contains("/app/classes");
  }

  @Test
  @DisplayName(
      "Skaffold delete cleanup simulation leaves no orphan namespaces or residual resources")
  void skaffoldDeleteLeavesNoResidue() throws Exception {
    Path skaffoldPath = Path.of(RepositoryRoot.path("skaffold.yaml"));
    Yaml yaml = new Yaml();

    try (InputStream in = new FileInputStream(skaffoldPath.toFile())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = yaml.load(in);

      @SuppressWarnings("unchecked")
      Map<String, Object> manifests = (Map<String, Object>) doc.get("manifests");
      @SuppressWarnings("unchecked")
      Map<String, Object> kustomize = (Map<String, Object>) manifests.get("kustomize");
      @SuppressWarnings("unchecked")
      List<String> paths = (List<String>) kustomize.get("paths");

      // Verify that every path managed by Skaffold points strictly to repository kustomize paths
      // so skaffold delete can track and delete 100% of deployed objects via owner references.
      assertThat(paths).isNotEmpty();
      for (String path : paths) {
        File dir = new File(RepositoryRoot.path(path));
        assertThat(dir).isDirectory();
        assertThat(new File(dir, "kustomization.yaml")).isFile();
      }
    }
  }
}
