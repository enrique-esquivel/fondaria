package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No service wires a raw {@code Clock.systemUTC()} — a check, not a convention, in the same shape
 * as {@code NoAmbientClockInRulesTest} and {@code NoIncrByFloatTest}.
 *
 * <h2>Why this is worth a test</h2>
 *
 * <p>Six services independently wrote the identical {@code return Clock.systemUTC();} in their
 * application class, and every one of them was wrong in the same way on Linux — see {@link
 * StorableClock}. A seventh service will write the same line, because it is the obvious line to
 * write, and the failure it causes is invisible on a developer's machine and cosmetic-looking in a
 * diff. {@link StorableClockTest} proves the wrapper truncates; this proves the wrapper is actually
 * reached.
 *
 * <h2>Why it scans the repository rather than this module</h2>
 *
 * <p>The rule is about the services, and the type it is defending lives here. Scoping it to {@code
 * platform-common}'s own sources would make it a check on a module that has no clock bean at all.
 * The repository root is located by walking up to {@code .git}, the same way {@code
 * LedgerEventSchemaCompatibilityTest} finds {@code contracts/}; nothing here depends on where the
 * checkout lives.
 */
class NoRawSystemClockTest {

  /**
   * {@code Clock.systemUTC()}, {@code Clock.systemDefaultZone()} and {@code Clock.system(zone)}.
   * {@code StorableClock.systemUTC()} is deliberately not matched — the {@code \bClock} boundary
   * requires the receiver to be {@code Clock} itself.
   */
  private static final Pattern RAW_SYSTEM_CLOCK =
      Pattern.compile("(?<![A-Za-z0-9_.])Clock\\s*\\.\\s*system(UTC|DefaultZone)?\\s*\\(");

  /** Javadoc here and in {@link StorableClock} names the forbidden call in order to explain it. */
  private static final List<String> COMMENT_PREFIXES = List.of("*", "//", "/*");

  @Test
  @DisplayName("no service wires a clock that can outrun what PostgreSQL stores")
  void noServiceWiresARawSystemClock() throws IOException {
    Path services = repositoryRoot().resolve("services");
    assertThat(services).isDirectory();

    List<String> offenders = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(services)) {
      List<Path> javaFiles =
          tree.filter(path -> path.toString().endsWith(".java"))
              .filter(path -> path.toString().contains(separated("src", "main", "java")))
              .toList();
      assertThat(javaFiles).as("the services tree should not be empty").isNotEmpty();

      for (Path file : javaFiles) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (isComment(line)) {
            continue;
          }
          if (RAW_SYSTEM_CLOCK.matcher(line).find()) {
            offenders.add(repositoryRoot().relativize(file) + ":" + (i + 1) + " — " + line.trim());
          }
        }
      }
    }

    assertThat(offenders)
        .as(
            "a service must wire StorableClock.systemUTC(): a raw system clock reports nanoseconds "
                + "on Linux, PostgreSQL stores microseconds, and the same resource then renders "
                + "differently depending on which layer answered it")
        .isEmpty();
  }

  private static boolean isComment(String line) {
    String trimmed = line.trim();
    return COMMENT_PREFIXES.stream().anyMatch(trimmed::startsWith);
  }

  private static String separated(String... segments) {
    return String.join(java.io.File.separator, segments);
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Path.of("").toAbsolutePath());
  }
}
