package dev.fondaria.rules;

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
 * Task 21.1 acceptance criterion 3: "grep for {@code LocalDate.now()} / {@code Instant.now()} in
 * dealing-date logic returns nothing". This is that grep, run as a portable JUnit check under plain
 * {@code make verify} rather than as a shell script — the same shape {@code
 * ForbiddenNumericTypesTest} uses for the equivalent criterion in {@code libs/valuation}.
 *
 * <h2>Why the whole module and not just {@code DealingDateRule}</h2>
 *
 * <p>Because the rule that bites is broader than one class. {@code libs/rules-engine} evaluates
 * effective-dated rules, and every one of them is resolved <i>as of a date the caller supplies</i>
 * — a plan version as of a batch's value date, a vesting position as of an employment start date, a
 * dealing date as of the instant a batch became dealable. A rule that reads the ambient clock stops
 * being reproducible: AGENTS.md §4 requires that a calculation be re-derivable years later from
 * stored data, never by rerunning current code against today's date, and the demo's accelerated
 * clock would drive a calendar this module then quietly ignored.
 *
 * <p>Scoping this to the dealing-date classes alone would also make it a check that rots — the next
 * rule added to the module would sit outside it by default. Module-wide, a new rule is covered the
 * moment it is written.
 */
class NoAmbientClockInRulesTest {

  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bLocalDate\\s*\\.\\s*now\\s*\\("),
          Pattern.compile("\\bLocalDateTime\\s*\\.\\s*now\\s*\\("),
          Pattern.compile("\\bInstant\\s*\\.\\s*now\\s*\\("),
          Pattern.compile("\\bZonedDateTime\\s*\\.\\s*now\\s*\\("),
          Pattern.compile("\\bOffsetDateTime\\s*\\.\\s*now\\s*\\("),
          Pattern.compile("\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\("));

  /**
   * Comment prefixes. Javadoc in this module explains at length <i>why</i> the ambient clock is
   * banned, and a check that fails on its own rule being written down is a check somebody deletes.
   */
  private static final List<String> COMMENT_PREFIXES = List.of("*", "//", "/*");

  @Test
  @DisplayName("no rule reads the ambient clock")
  void noRuleReadsTheAmbientClock() throws IOException {
    Path mainSources = Path.of("src", "main", "java");
    assertThat(mainSources).isDirectory();

    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainSources)) {
      List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();

      // A scan that found nothing to scan proves nothing.
      assertThat(javaFiles).isNotEmpty();

      for (Path file : javaFiles) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (isComment(line)) {
            continue;
          }
          for (Pattern forbidden : FORBIDDEN) {
            if (forbidden.matcher(line).find()) {
              offenders.add(file + ":" + (i + 1) + " " + line.strip());
            }
          }
        }
      }
    }

    assertThat(offenders)
        .withFailMessage(
            "A rule in libs/rules-engine reads the ambient clock. Every date a rule needs is an"
                + " argument: a dealing date comes from the batch's value date and the instant it"
                + " was staged (Task 21.1), never from today. Found at: %s",
            offenders)
        .isEmpty();
  }

  private static boolean isComment(String line) {
    String trimmed = line.strip();
    return COMMENT_PREFIXES.stream().anyMatch(trimmed::startsWith);
  }
}
