package dev.fondaria.eodjob.alerts;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * Task 7.6's other two acceptance criteria: {@code promtool check rules} passes, and each alert is
 * rehearsed once against an injected fault ({@code promtool test rules}).
 *
 * <p>{@code promtool} is a CLI, not a JVM library, so this runs it the same way any other hermetic,
 * pinned tool this codebase does not otherwise depend on would be run: a one-shot container. {@link
 * OneShotStartupCheckStrategy} is built for exactly this — it starts a container meant to run to
 * completion once, and {@link GenericContainer#start()} throws unless the exit code is 0, so a
 * syntax error or a failed rehearsal fails this test the same way any other assertion would, with
 * promtool's own diagnostic on stderr in the container log.
 *
 * <p>{@code prom/prometheus} is deliberately not added to {@code ContainerImages} (libs/testing):
 * every constant there names a running {@code docker-compose.yml} service ({@code
 * ContainerImagesTest} asserts each by name), and Prometheus is not one -- Phase 11 Task 11.1 is
 * what adds a running Prometheus to the stack. This image is pulled solely for the {@code promtool}
 * binary it ships and never runs as a server, so it is pinned here instead, independently of
 * whatever version Phase 11 eventually chooses to run.
 */
@Tag("integration")
class EodCloseAlertRulesIntegrationTest {

  private static final String PROMTOOL_IMAGE = "prom/prometheus:v2.55.1";
  private static final String CONTAINER_RULES_DIR = "/rules";

  @Test
  void rulesFileIsSyntacticallyValid() {
    runPromtool("check", "rules", CONTAINER_RULES_DIR + "/eod-close.rules.yml");
  }

  @Test
  void everyAlertFiresUnderItsInjectedFault() {
    runPromtool("test", "rules", CONTAINER_RULES_DIR + "/eod-close.tests.yml");
  }

  /**
   * Fails the test (via {@link GenericContainer#start()} throwing) unless {@code promtool} exits 0.
   * Both rule files are copied in rather than bind-mounted so this works identically on a CI
   * runner's Docker daemon and a developer's, with no host-path translation to get wrong.
   */
  private void runPromtool(String... promtoolArgs) {
    Path alertsDir = RepoRoot.find().resolve("infrastructure/observability/alerts");
    try (GenericContainer<?> promtool =
        new GenericContainer<>(PROMTOOL_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("eod-close.rules.yml")),
                CONTAINER_RULES_DIR + "/eod-close.rules.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(alertsDir.resolve("eod-close.tests.yml")),
                CONTAINER_RULES_DIR + "/eod-close.tests.yml")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withEntrypoint("promtool").withCmd(promtoolArgs))
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      promtool.start();
    }
  }
}
