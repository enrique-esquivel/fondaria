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
 * Task 10.2 (phase-10-edge.md) acceptance criterion "JWKS unreachable -> fail closed and alert" --
 * the alert half. Mirrors {@link FinancialControlAlertRulesIntegrationTest}'s Promtool CLI
 * container approach for {@code edge-security.rules.yml}.
 */
@Tag("integration")
class EdgeSecurityAlertRulesIntegrationTest {

  private static final String PROMTOOL_IMAGE = "prom/prometheus:v2.55.1";
  private static final String CONTAINER_RULES_DIR = "/rules";

  @Test
  @DisplayName("promtool check rules passes on edge-security.rules.yml")
  void rulesFileIsSyntacticallyValid() {
    runPromtool("check", "rules", CONTAINER_RULES_DIR + "/edge-security.rules.yml");
  }

  @Test
  @DisplayName("promtool test rules passes on edge-security.tests.yml")
  void alertFiresOnSustainedFailureAndStaysQuietOnAnOrdinaryStartupBlip() {
    runPromtool("test", "rules", CONTAINER_RULES_DIR + "/edge-security.tests.yml");
  }

  private void runPromtool(String... promtoolArgs) {
    Path alertsDir = Path.of(RepositoryRoot.path("infrastructure/observability/alerts"));
    try (GenericContainer<?> promtool =
        new GenericContainer<>(PROMTOOL_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("edge-security.rules.yml")),
                CONTAINER_RULES_DIR + "/edge-security.rules.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("edge-security.tests.yml")),
                CONTAINER_RULES_DIR + "/edge-security.tests.yml")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withEntrypoint("promtool").withCmd(promtoolArgs))
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      promtool.start();
    }
  }
}
