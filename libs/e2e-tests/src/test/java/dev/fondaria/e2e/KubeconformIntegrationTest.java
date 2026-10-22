package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.testing.ContainerImages;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * Task 12.1 acceptance criterion: kubeconform validates rendered Kustomize manifests against the
 * target Kubernetes version (1.31.0).
 */
@Tag("integration")
class KubeconformIntegrationTest {

  private static final String K8S_VERSION = "1.31.0";
  private static final String CONTAINER_MANIFESTS_DIR = "/manifests";

  @Test
  @DisplayName("kubeconform validates all base manifests against target Kubernetes version")
  void kubeconformValidatesAllBaseManifests() throws Exception {
    Path basePath = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
    Path tempDir = Files.createTempDirectory("kubeconform-rendered-base-");
    Path renderedFile = tempDir.resolve("all-base.yaml");

    renderKustomize(basePath, renderedFile);
    validateWithKubeconform(renderedFile);
  }

  @Test
  @DisplayName("kubeconform validates local overlay manifests against target Kubernetes version")
  void kubeconformValidatesLocalOverlayManifests() throws Exception {
    Path localPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local"));
    Path tempDir = Files.createTempDirectory("kubeconform-rendered-local-");
    Path renderedFile = tempDir.resolve("all-local.yaml");

    renderKustomize(localPath, renderedFile);
    validateWithKubeconform(renderedFile);
  }

  @Test
  @DisplayName("kubeconform validates prod overlay manifests against target Kubernetes version")
  void kubeconformValidatesProdOverlayManifests() throws Exception {
    Path prodPath = Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/prod"));
    Path tempDir = Files.createTempDirectory("kubeconform-rendered-prod-");
    Path renderedFile = tempDir.resolve("all-prod.yaml");

    renderKustomize(prodPath, renderedFile);
    validateWithKubeconform(renderedFile);
  }

  private void renderKustomize(Path targetPath, Path outputFile) throws Exception {
    String kubectlPath = findExecutable("kubectl");
    if (kubectlPath != null) {
      Process process =
          new ProcessBuilder(kubectlPath, "kustomize", targetPath.toString())
              .redirectOutput(outputFile.toFile())
              .start();
      int exit = process.waitFor();
      assertThat(exit).as("kubectl kustomize on %s must succeed", targetPath).isZero();
    } else {
      StringBuilder combined = new StringBuilder();
      try (var stream = Files.walk(targetPath)) {
        for (Path p : stream.filter(Files::isRegularFile).toList()) {
          if (p.toString().endsWith(".yaml") && !p.endsWith("kustomization.yaml")) {
            combined.append(Files.readString(p)).append("\n---\n");
          }
        }
      }
      Files.writeString(outputFile, combined.toString());
    }
  }

  private void validateWithKubeconform(Path renderedFile) {
    ToStringConsumer stdout = new ToStringConsumer();
    ToStringConsumer stderr = new ToStringConsumer();

    try (GenericContainer<?> kubeconform =
        new GenericContainer<>(ContainerImages.KUBECONFORM)
            .withCopyFileToContainer(
                MountableFile.forHostPath(renderedFile), CONTAINER_MANIFESTS_DIR + "/rendered.yaml")
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.withEntrypoint("/kubeconform")
                        .withCmd(
                            "-summary",
                            "-output",
                            "text",
                            "-kubernetes-version",
                            K8S_VERSION,
                            "-ignore-missing-schemas",
                            CONTAINER_MANIFESTS_DIR + "/rendered.yaml"))
            .withLogConsumer(stdout)
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      kubeconform.start();
    }

    String output = stdout.toUtf8String() + "\n" + stderr.toUtf8String();
    assertThat(output).contains("Summary:");
    assertThat(output).contains("Errors: 0");
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
    for (String standardDir :
        java.util.List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")) {
      File file = new File(standardDir, name);
      if (file.canExecute()) {
        return file.getAbsolutePath();
      }
    }
    return null;
  }
}
