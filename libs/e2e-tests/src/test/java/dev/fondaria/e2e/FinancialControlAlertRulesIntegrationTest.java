package dev.fondaria.e2e;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * Task 11.4: acceptance criteria verification via Promtool CLI container:
 *
 * <ul>
 *   <li>{@code promtool check rules} validates syntax and PromQL AST
 *   <li>{@code promtool test rules} executes unit test suites verifying normal-day baseline (0
 *       alerts), idle discrimination, and injected fault response for all alerts
 * </ul>
 */
@Tag("integration")
class FinancialControlAlertRulesIntegrationTest {

  private static final String PROMTOOL_IMAGE = "prom/prometheus:v2.55.1";
  private static final String CONTAINER_RULES_DIR = "/rules";

  @Test
  @DisplayName("promtool check rules passes on financial-control.rules.yml")
  void rulesFileIsSyntacticallyValid() {
    runPromtool("check", "rules", CONTAINER_RULES_DIR + "/financial-control.rules.yml");
  }

  @Test
  @DisplayName("promtool test rules passes on financial-control.tests.yml")
  void everyAlertFiresUnderItsInjectedFaultAndZeroDuringNormalDay() {
    runPromtool("test", "rules", CONTAINER_RULES_DIR + "/financial-control.tests.yml");
  }

  @Test
  @DisplayName("promtool check config passes on prometheus.yml")
  void prometheusConfigIsSyntacticallyValid() {
    runPromtool("check", "config", CONTAINER_RULES_DIR + "/prometheus.yml");
  }

  private void runPromtool(String... promtoolArgs) {
    Path alertsDir = Path.of(RepositoryRoot.path("infrastructure/observability/alerts"));
    Path promDir = Path.of(RepositoryRoot.path("infrastructure/observability/prometheus"));
    try (GenericContainer<?> promtool =
        new GenericContainer<>(PROMTOOL_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("financial-control.rules.yml")),
                CONTAINER_RULES_DIR + "/financial-control.rules.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("financial-control.tests.yml")),
                CONTAINER_RULES_DIR + "/financial-control.tests.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(promDir.resolve("prometheus.yml")),
                CONTAINER_RULES_DIR + "/prometheus.yml")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withEntrypoint("promtool").withCmd(promtoolArgs))
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      promtool.start();
    }
  }
}
