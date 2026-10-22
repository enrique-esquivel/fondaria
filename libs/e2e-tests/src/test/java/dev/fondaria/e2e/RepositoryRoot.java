package dev.fondaria.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the repository root by walking up from the working directory, so a test can mount a
 * production script — {@code infrastructure/compose/cdc/run.sh} — into a container without a
 * machine-specific path (AGENTS.md §10).
 */
final class RepositoryRoot {

  private RepositoryRoot() {}

  static String path(String relative) {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      if (Files.exists(directory.resolve("infrastructure/compose/docker-compose.yml"))) {
        return directory.resolve(relative).toString();
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Paths.get("").toAbsolutePath());
  }
}
