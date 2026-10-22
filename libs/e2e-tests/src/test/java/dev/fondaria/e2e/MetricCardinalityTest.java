package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Task 11.2's "cardinality bounded — no unbounded label such as account id" acceptance criterion,
 * checked structurally across every service rather than trusted to review: a static scan for a
 * metric tag key shaped like an identifier that grows without bound, the same idea as {@code
 * NoRawSystemClockTest} and {@code NoAmbientClockInRulesTest}.
 *
 * <p>Deliberately narrow to <b>metric</b> tag call sites ({@code Counter.builder(...).tag(...)},
 * {@code Tags.of(...)}, and {@code registry.counter(name, "key", value)} — the two-string-literal
 * form this task's own {@code CashReceiptTelemetry}/{@code AllotmentMetrics} use for a dynamically
 * looked-up counter) — a span attribute is a different concern ({@link
 * dev.fondaria.common.tracing.TelemetryRedactionFilter} covers privacy there, and Tempo does not
 * suffer Prometheus's cardinality explosion), so a {@code transferId} on a span is fine and this
 * scan does not look at spans at all.
 */
class MetricCardinalityTest {

  /** Matches an open-ended identifier shape, wherever it appears as a tag key's literal name. */
  private static final Set<String> UNBOUNDED_KEY_FRAGMENTS =
      Set.of("accountid", "participantid", "organizationid", "transferid", "idempotencykey");

  private static final Pattern TAG_CALL =
      Pattern.compile(
          "\\.tag\\(\\s*\"([^\"]+)\""
              + "|Tags\\.of\\(\\s*\"([^\"]+)\""
              + "|\\.counter\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"");

  @Test
  void noMetricTagKeyIsAnUnboundedIdentifier() {
    Path servicesRoot = Path.of(RepositoryRoot.path("services"));
    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(servicesRoot)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> p.toString().contains("/src/main/java/"))
          .forEach(path -> scan(path, violations));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    assertThat(violations)
        .as("a metric tag keyed by an open-ended identifier grows without bound in Prometheus")
        .isEmpty();
  }

  private static void scan(Path path, List<String> violations) {
    String content;
    try {
      content = Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Matcher matcher = TAG_CALL.matcher(content);
    while (matcher.find()) {
      String key = firstGroup(matcher);
      String normalized = key.toLowerCase(Locale.ROOT);
      for (String fragment : UNBOUNDED_KEY_FRAGMENTS) {
        if (normalized.contains(fragment)) {
          violations.add(path + ": tag key \"" + key + "\"");
        }
      }
    }
  }

  private static String firstGroup(Matcher matcher) {
    for (int group = 1; group <= matcher.groupCount(); group++) {
      String value = matcher.group(group);
      if (value != null) {
        return value;
      }
    }
    throw new IllegalStateException("TAG_CALL matched with no capturing group populated");
  }
}
