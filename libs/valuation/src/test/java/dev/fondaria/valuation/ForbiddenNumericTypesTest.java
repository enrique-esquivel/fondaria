package dev.fondaria.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/ENGINEERING-RULES.md §4: never {@code double}, {@code float} or {@code BigDecimal} for cash,
 * units or NAV — in storage, transport or computation. Task 20.2 acceptance criterion 4 asks for
 * exactly this, as a grep; this is that grep, run as a portable JUnit check instead of a shell
 * script so it runs wherever {@code make verify} does.
 */
class ForbiddenNumericTypesTest {

  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bdouble\\b"),
          Pattern.compile("\\bfloat\\b"),
          Pattern.compile("\\bBigDecimal\\b"));

  @Test
  void noForbiddenNumericTypeAppearsInMainSources() throws IOException {
    Path mainSources = Path.of("src", "main", "java");
    assertThat(mainSources).isDirectory();

    try (Stream<Path> files = Files.walk(mainSources)) {
      List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
      assertThat(javaFiles).isNotEmpty();

      for (Path file : javaFiles) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          for (Pattern forbidden : FORBIDDEN) {
            assertThat(forbidden.matcher(line).find())
                .withFailMessage(
                    "%s:%d matches forbidden pattern %s: %s",
                    file, i + 1, forbidden.pattern(), line)
                .isFalse();
          }
        }
      }
    }
  }
}
