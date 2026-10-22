package dev.fondaria.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards {@code contracts/openapi/contribution-api.yaml} — the contract Task D.0's fund, plan and
 * participant-lookup endpoints were added to. Not a rubber stamp: every {@code $ref} is resolved
 * against {@code components/schemas} (a typo here is a client generator failure nobody notices
 * until someone tries to generate from it), and every {@code operationId} is unique across the
 * document.
 */
class OpenApiContractTest {

  private static final String RELATIVE_PATH = "contracts/openapi/contribution-api.yaml";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadContract() {
    Path path = repositoryRoot().resolve(RELATIVE_PATH);
    try (InputStream in = Files.newInputStream(path)) {
      return new Yaml().load(in);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + path, e);
    }
  }

  @Test
  @DisplayName("the contract is well-formed YAML declaring OpenAPI 3.0.3")
  void isWellFormed() {
    Map<String, Object> contract = loadContract();

    assertThat(contract.get("openapi")).isEqualTo("3.0.3");
    assertThat(contract).containsKeys("paths", "components");
  }

  @Test
  @DisplayName("Task D.0's three endpoints are present with the operations the task names")
  void taskD0EndpointsArePresent() {
    Map<String, Object> paths = (Map<String, Object>) loadContract().get("paths");

    assertThat(operationsOf(paths, "/v1/funds")).containsExactly("post");
    assertThat(operationsOf(paths, "/v1/organizations/{organizationId}/plans"))
        .containsExactly("post");
    assertThat(operationsOf(paths, "/v1/organizations/{organizationId}/participants"))
        .containsExactly("get");
  }

  @Test
  @DisplayName("every $ref in the document resolves to a real component")
  void everyRefResolves() {
    Map<String, Object> contract = loadContract();
    Set<String> definedSchemas =
        ((Map<String, Object>) navigate(contract, "components", "schemas")).keySet();
    Set<String> definedParameters =
        ((Map<String, Object>) navigate(contract, "components", "parameters")).keySet();
    Set<String> definedResponses =
        ((Map<String, Object>) navigate(contract, "components", "responses")).keySet();

    List<String> unresolved = new ArrayList<>();
    for (String ref : collectRefs(contract)) {
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      boolean resolved =
          (ref.contains("/schemas/") && definedSchemas.contains(name))
              || (ref.contains("/parameters/") && definedParameters.contains(name))
              || (ref.contains("/responses/") && definedResponses.contains(name));
      if (!resolved) {
        unresolved.add(ref);
      }
    }

    assertThat(unresolved).as("every $ref must resolve to a defined component").isEmpty();
  }

  @Test
  @DisplayName(
      "no schema property is typed as a JSON number (AGENTS.md §4: never a float or a decimal)")
  void noSchemaPropertyIsANumber() {
    Map<String, Object> schemas =
        (Map<String, Object>) navigate(loadContract(), "components", "schemas");

    List<String> offending = new ArrayList<>();
    for (Map.Entry<String, Object> schema : schemas.entrySet()) {
      collectNumberTypedProperties(schema.getValue(), schema.getKey(), offending);
    }

    assertThat(offending)
        .as("cash, units and NAV are integers everywhere, including in transport")
        .isEmpty();
  }

  @Test
  @DisplayName("every operationId is unique")
  void operationIdsAreUnique() {
    Map<String, Object> paths = (Map<String, Object>) loadContract().get("paths");
    List<String> operationIds = new ArrayList<>();
    for (Object pathItem : paths.values()) {
      for (Object operation : ((Map<String, Object>) pathItem).values()) {
        if (operation instanceof Map<?, ?> op && op.get("operationId") instanceof String id) {
          operationIds.add(id);
        }
      }
    }

    assertThat(operationIds).doesNotHaveDuplicates();
  }

  @SuppressWarnings("unchecked")
  private static Set<String> operationsOf(Map<String, Object> paths, String path) {
    assertThat(paths).containsKey(path);
    return ((Map<String, Object>) paths.get(path)).keySet();
  }

  @SuppressWarnings("unchecked")
  private static Object navigate(Map<String, Object> root, String... keys) {
    Object current = root;
    for (String key : keys) {
      current = ((Map<String, Object>) current).get(key);
    }
    return current;
  }

  /**
   * Walks a schema (and any nested {@code items}/{@code properties}) looking for {@code type:
   * number}.
   */
  @SuppressWarnings("unchecked")
  private static void collectNumberTypedProperties(
      Object node, String path, List<String> offending) {
    if (!(node instanceof Map<?, ?> map)) {
      return;
    }
    if ("number".equals(map.get("type"))) {
      offending.add(path);
    }
    Object properties = map.get("properties");
    if (properties instanceof Map<?, ?> propertyMap) {
      for (Map.Entry<?, ?> entry : propertyMap.entrySet()) {
        collectNumberTypedProperties(entry.getValue(), path + "." + entry.getKey(), offending);
      }
    }
    Object items = map.get("items");
    if (items != null) {
      collectNumberTypedProperties(items, path + "[]", offending);
    }
  }

  /** Walks every map and list in the document looking for a {@code $ref} string value. */
  private static List<String> collectRefs(Object node) {
    List<String> refs = new ArrayList<>();
    Deque<Object> stack = new ArrayDeque<>();
    stack.push(node);
    while (!stack.isEmpty()) {
      Object current = stack.pop();
      if (current instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref) {
            refs.add(ref);
          } else {
            stack.push(entry.getValue());
          }
        }
      } else if (current instanceof List<?> list) {
        stack.addAll(list);
      }
    }
    return refs;
  }

  private static Path repositoryRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.exists(directory.resolve("infrastructure/compose/docker-compose.yml"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Path.of("").toAbsolutePath());
  }
}
