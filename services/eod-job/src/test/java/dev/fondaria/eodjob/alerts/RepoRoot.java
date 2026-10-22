package dev.fondaria.eodjob.alerts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Finds the repository root by walking up from the working directory, the same technique {@code
 * ContainerImagesTest} (libs/testing) uses for {@code docker-compose.yml} -- shared here because
 * both {@link AlertRunbookLinksTest} and {@link EodCloseAlertRulesIntegrationTest} need it for the
 * same file.
 */
final class RepoRoot {

  private static final String MARKER = "infrastructure/observability/alerts/eod-close.rules.yml";

  private RepoRoot() {}

  static Path find() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve(MARKER))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "could not find " + MARKER + " above " + Paths.get("").toAbsolutePath());
  }
}
