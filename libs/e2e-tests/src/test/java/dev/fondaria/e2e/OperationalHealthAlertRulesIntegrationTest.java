package dev.fondaria.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code operational-health.rules.yml} through the Promtool CLI container, as {@link
 * FinancialControlAlertRulesIntegrationTest} does for its own rules: {@code check rules} for syntax
 * and PromQL, {@code test rules} for each alert's quiet day and its fault on both sides of the
 * {@code for:} boundary. That every metric the rules read really exists is {@link
 * OperationalHealthMetricNamesAreRealIntegrationTest}'s.
 */
@Tag("integration")
class OperationalHealthAlertRulesIntegrationTest {

  private static final String PROMTOOL_IMAGE = "prom/prometheus:v2.55.1";
  private static final String CONTAINER_RULES_DIR = "/rules";

  @Test
  @DisplayName("promtool check rules passes on operational-health.rules.yml")
  void rulesFileIsSyntacticallyValid() {
    runPromtool("check", "rules", CONTAINER_RULES_DIR + "/operational-health.rules.yml");
  }

  @Test
  @DisplayName("promtool test rules passes on operational-health.tests.yml")
  void everyAlertFiresOnItsFaultAndStaysQuietOnAnOrdinaryDay() {
    runPromtool("test", "rules", CONTAINER_RULES_DIR + "/operational-health.tests.yml");
  }

  // A scrape-config file is not a full config: promtool checks each set through a config that
  // includes it the way prometheus.yml's scrape_config_files does. Compose and Kubernetes are
  // checked apart because both define the `rabbitmq` job, each for its own environment.
  @Test
  @DisplayName("promtool check config passes on the compose scrape.d files")
  void composeScrapeFilesAreValid() {
    runPromtool("check", "config", CONTAINER_RULES_DIR + "/compose-scrape.yml");
  }

  @Test
  @DisplayName("promtool check config passes on the Kubernetes prometheus-scrape-k8s files")
  void kubernetesScrapeFilesAreValid() {
    runPromtool("check", "config", CONTAINER_RULES_DIR + "/k8s-scrape.yml");
  }

  private void runPromtool(String... promtoolArgs) {
    Path alertsDir = Path.of(RepositoryRoot.path("infrastructure/observability/alerts"));
    Path composeScrapeDir =
        Path.of(RepositoryRoot.path("infrastructure/observability/prometheus/scrape.d"));
    try (GenericContainer<?> promtool =
        new GenericContainer<>(PROMTOOL_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("operational-health.rules.yml")),
                CONTAINER_RULES_DIR + "/operational-health.rules.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("operational-health.tests.yml")),
                CONTAINER_RULES_DIR + "/operational-health.tests.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(composeScrapeDir), "/etc/prometheus/scrape.d")
            .withCopyToContainer(
                Transferable.of("scrape_config_files:\n  - /etc/prometheus/scrape.d/*.yml\n"),
                CONTAINER_RULES_DIR + "/compose-scrape.yml")
            .withCopyToContainer(
                Transferable.of("scrape_config_files:\n  - /etc/prometheus/scrape-k8s/*.yml\n"),
                CONTAINER_RULES_DIR + "/k8s-scrape.yml")
            // cadvisor.yml authenticates with the pod's service-account token, and promtool
            // refuses a config whose credentials file does not exist.
            .withCopyToContainer(
                Transferable.of("placeholder"),
                "/var/run/secrets/kubernetes.io/serviceaccount/token")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withEntrypoint("promtool").withCmd(promtoolArgs))
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      kubernetesScrapeFiles()
          .forEach(
              (name, content) ->
                  promtool.withCopyToContainer(
                      Transferable.of(content), "/etc/prometheus/scrape-k8s/" + name));
      promtool.start();
    }
  }

  /** The data of the prometheus-scrape-k8s ConfigMap: file name to scrape-config file. */
  @SuppressWarnings("unchecked")
  private static Map<String, String> kubernetesScrapeFiles() {
    Path configMap =
        Path.of(
            RepositoryRoot.path("infrastructure/k8s/base/observability/prometheus/configmap.yaml"));
    try (InputStream in = Files.newInputStream(configMap)) {
      for (Object doc : new Yaml().loadAll(in)) {
        Map<String, Object> map = (Map<String, Object>) doc;
        if ("prometheus-scrape-k8s"
            .equals(((Map<String, Object>) map.get("metadata")).get("name"))) {
          return (Map<String, String>) map.get("data");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new AssertionError("no prometheus-scrape-k8s ConfigMap in " + configMap);
  }
}
