package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ties {@code contracts/events/close-signed-off-v1.schema.json} to its example and to the {@link
 * CloseSignedOffEvent} the service actually publishes — the same guard {@code
 * LedgerEventSchemaConformanceTest} is for the ledger event schemas.
 *
 * <ul>
 *   <li>the schema is {@code additionalProperties: false} with every property {@code required}, so
 *       "the example carries exactly the schema's fields" is the whole structural check;
 *   <li>a {@link CloseSignedOffEvent} built from the example's values renders — <b>in the same
 *       field order</b> — back to the example, so a field added to the record, or reordered in
 *       {@code toJson}, without a matching schema/example change fails here.
 * </ul>
 */
class CloseSignedOffEventConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("the schema declares every property required, and the example carries exactly them")
  void exampleMatchesSchemaFields() {
    JsonNode schema = read("close-signed-off-v1.schema.json");
    JsonNode example = read("close-signed-off-v1.example.json");

    TreeSet<String> schemaProperties = new TreeSet<>();
    schema.get("properties").fieldNames().forEachRemaining(schemaProperties::add);

    TreeSet<String> required = new TreeSet<>();
    schema.get("required").forEach(n -> required.add(n.asText()));

    TreeSet<String> exampleFields = new TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);

    assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    assertThat(required).isEqualTo(schemaProperties);
    assertThat(exampleFields).isEqualTo(schemaProperties);
    assertThat(schema.get("properties").get("schemaVersion").get("const").asText())
        .isEqualTo(CloseSignoffTopic.SCHEMA_VERSION);
  }

  @Test
  @DisplayName("the event the service publishes renders back to the committed example")
  void theEventRendersToTheExample() {
    JsonNode example = read("close-signed-off-v1.example.json");

    CloseSignedOffEvent event =
        new CloseSignedOffEvent(
            LocalDate.parse(example.get("businessDate").asText()),
            Instant.parse(example.get("signedOffAt").asText()),
            UUID.fromString(example.get("reconciliationRunId").asText()),
            UUID.fromString(example.get("custodianRunId").asText()),
            UUID.fromString(example.get("snapshotRunId").asText()),
            example.get("snapshotObjectKey").asText(),
            example.get("snapshotManifestKey").asText(),
            example.get("snapshotPositionCount").asLong(),
            example.get("lastBusinessDayOfMonth").asBoolean());

    JsonNode rendered = readTree(event.toJson());
    assertThat(rendered).as("same fields and values").isEqualTo(example);
    assertThat(fieldOrder(rendered))
        .as("same field order — the wire shape is a property of toJson, not of a serializer")
        .isEqualTo(fieldOrder(example));
    assertThat(event.key()).isEqualTo(example.get("businessDate").asText());
  }

  private static List<String> fieldOrder(JsonNode object) {
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static JsonNode read(String name) {
    Path path = repositoryRoot().resolve("contracts/events").resolve(name);
    try {
      return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + name, e);
    }
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new IllegalStateException("could not parse rendered event", e);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("could not locate the repository root");
  }
}
