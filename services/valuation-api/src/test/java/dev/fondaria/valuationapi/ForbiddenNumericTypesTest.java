package dev.fondaria.valuationapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md §4: never {@code double}, {@code float} or {@code BigDecimal} for cash, units or NAV —
 * in storage, transport or computation.
 *
 * <p>The same check {@code libs/valuation} runs, applied to the service that receives the numbers.
 * It matters most here: this module is where a provider's file is parsed, and a decimal amount is
 * refused rather than rounded ({@code DECIMAL_AMOUNT}) precisely because there is no floating-point
 * type available to round it into.
 */
class ForbiddenNumericTypesTest {

  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bdouble\\b"),
          Pattern.compile("\\bfloat\\b"),
          Pattern.compile("\\bBigDecimal\\b"));

  @Test
  @DisplayName("no forbidden numeric type appears anywhere in this service's main sources")
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
