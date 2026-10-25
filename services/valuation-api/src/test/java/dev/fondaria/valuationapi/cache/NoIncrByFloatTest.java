package dev.fondaria.valuationapi.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docs/ENGINEERING-RULES.md §4: "No {@code INCRBYFLOAT} in Redis. Integer operations only, inside a
 * Lua script." An explicit acceptance criterion of Task 20.6 — "repository grep for {@code
 * INCRBYFLOAT} returns nothing (CI check)".
 *
 * <p><b>Repository-wide, not module-wide</b>, unlike {@code ForbiddenNumericTypesTest} — the
 * criterion says repository, and the rule is about anything that could ever talk to Redis, not only
 * the module that happens to do so today. The root is found by walking up to the directory holding
 * {@code .git}; failing to find it fails the test rather than silently checking nothing, which is
 * the way this sort of check usually rots.
 *
 * <p>Runs under plain {@code make verify} with no container, which is what makes it a CI check
 * rather than a convention.
 */
class NoIncrByFloatTest {

  /** Split so this file's own occurrences cannot match the scan it performs. */
  private static final String FORBIDDEN = "INCRBY" + "FLOAT";

  /**
   * Code only. Prose is excluded deliberately: {@code docs/ENGINEERING-RULES.md}, the phase
   * documents and the design notes all name the command in order to forbid it, and a check that
   * fails on its own rule being written down is a check somebody deletes.
   */
  private static final List<String> SCANNED_EXTENSIONS =
      List.of(".java", ".lua", ".sql", ".yaml", ".yml", ".kt", ".sh");

  /**
   * Comment prefixes. A Redis call is never inside a comment, and javadoc explaining <i>why</i> the
   * command is banned is exactly the documentation this rule wants to encourage.
   */
  private static final List<String> COMMENT_PREFIXES = List.of("*", "//", "#", "--", "/*");

  private static final List<String> SKIPPED_DIRECTORIES =
      List.of("target", ".git", "node_modules", ".claude");

  @Test
  @DisplayName("INCRBYFLOAT appears nowhere in the repository")
  void incrByFloatAppearsNowhereInTheRepository() throws IOException {
    Path root = repositoryRoot();
    List<String> offenders = new ArrayList<>();

    try (Stream<Path> files = Files.walk(root)) {
      List<Path> candidates =
          files
              .filter(Files::isRegularFile)
              .filter(NoIncrByFloatTest::isScanned)
              .filter(path -> !isSkipped(root, path))
              .toList();

      // A scan that found nothing to scan proves nothing.
      assertThat(candidates).isNotEmpty();

      for (Path file : candidates) {
        if (Path.of(file.toString()).equals(thisSourceFile(root))) {
          continue;
        }
        List<String> lines = readLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (isComment(line)) {
            continue;
          }
          if (line.toUpperCase(Locale.ROOT).contains(FORBIDDEN)) {
            offenders.add(root.relativize(file) + ":" + (i + 1));
          }
        }
      }
    }

    assertThat(offenders)
        .withFailMessage(
            "%s is forbidden (docs/ENGINEERING-RULES.md §4): a NAV, a unit count and a cash amount are integers at"
                + " a fixed scale, and there is no Redis operation that may widen one into a"
                + " floating-point value. Found at: %s",
            FORBIDDEN, offenders)
        .isEmpty();
  }

  /** Unreadable or binary files are skipped rather than failing the scan. */
  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
  }

  private static boolean isComment(String line) {
    String trimmed = line.strip();
    return COMMENT_PREFIXES.stream().anyMatch(trimmed::startsWith);
  }

  private static boolean isScanned(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean isSkipped(Path root, Path path) {
    Path relative = root.relativize(path);
    for (Path segment : relative) {
      if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static Path thisSourceFile(Path root) {
    return root.resolve(
        "services/valuation-api/src/test/java/dev/fondaria/valuationapi/cache/NoIncrByFloatTest.java");
  }

  /**
   * Walks up from the module directory. Absolute paths are never hardcoded
   * (docs/ENGINEERING-RULES.md §10) — the repository is located by structure, so this works on any
   * machine and in CI.
   */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "Could not locate the repository root: no ancestor of "
            + Path.of("").toAbsolutePath()
            + " contains .git (a directory, or a file in a git worktree)");
  }
}
