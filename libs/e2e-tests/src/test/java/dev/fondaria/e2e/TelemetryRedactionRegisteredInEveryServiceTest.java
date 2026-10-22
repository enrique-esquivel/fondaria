package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every service module registers {@code TelemetryRedactionFilter} as an {@code ObservationFilter}
 * bean (Task 11.2), one hand-written {@code TracingConfiguration} class per service rather than a
 * single shared auto-configuration — a deliberate choice matching this repo's convention of small,
 * independently-duplicated per-service wiring over cross-service coupling (see {@code
 * TraceCorrelationPublisher}/{@code TraceCorrelationReader}'s own javadoc for the same reasoning).
 *
 * <p>That convention has one sharp edge this test exists to catch: unlike a missing metric or a
 * missing span, a service that forgets this registration fails <b>silently</b> — nothing breaks, no
 * test but this one notices, and every span/metric that service exports carries whatever AGENTS.md
 * §7 forbids (RFC, CURP, names, amounts, tokens) unredacted. A structural scan closes that gap the
 * same way {@link MetricCardinalityTest} closes the unbounded-tag-key one.
 */
class TelemetryRedactionRegisteredInEveryServiceTest {

  private static final Pattern REGISTERS_FILTER =
      Pattern.compile("new\\s+TelemetryRedactionFilter\\(\\s*\\)");

  @Test
  void everyServiceModuleRegistersTheRedactionFilter() throws IOException {
    Path servicesRoot = Path.of(RepositoryRoot.path("services"));
    List<String> missing = new ArrayList<>();

    try (Stream<Path> serviceDirs = Files.list(servicesRoot)) {
      for (Path serviceDir : serviceDirs.filter(Files::isDirectory).sorted().toList()) {
        if (isMavenModule(serviceDir) && !registersRedactionFilter(serviceDir)) {
          missing.add(serviceDir.getFileName().toString());
        }
      }
    }

    assertThat(missing)
        .as(
            "a service missing TelemetryRedactionFilter registration silently exports"
                + " unredacted spans and metrics (AGENTS.md §7)")
        .isEmpty();
  }

  private static boolean isMavenModule(Path serviceDir) {
    return Files.exists(serviceDir.resolve("pom.xml"));
  }

  private static boolean registersRedactionFilter(Path serviceDir) throws IOException {
    Path mainJava = serviceDir.resolve("src/main/java");
    if (!Files.isDirectory(mainJava)) {
      return false;
    }
    try (Stream<Path> files = Files.walk(mainJava)) {
      return files
          .filter(p -> p.toString().endsWith(".java"))
          .anyMatch(TelemetryRedactionRegisteredInEveryServiceTest::fileRegistersFilter);
    }
  }

  private static boolean fileRegistersFilter(Path path) {
    try {
      return REGISTERS_FILTER.matcher(Files.readString(path)).find();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
