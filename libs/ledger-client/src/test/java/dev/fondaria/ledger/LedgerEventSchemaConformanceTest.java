package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Ties the committed {@code contracts/events} artifacts together, end to end:
 *
 * <ul>
 *   <li>each {@code *-v1.example.json} carries exactly the fields its schema declares (the schemas
 *       are {@code additionalProperties: false} with every property {@code required}, so equality
 *       is the whole check);
 *   <li>the two bridge-output examples are consistent with the upstream {@code
 *       ledger-cdc-payload-0.17.4.example.json} they are derived from — same transfer id, amount
 *       and account ids — which is the "round-trips a real captured CDC message" acceptance
 *       criterion;
 *   <li>each example decodes through {@link LedgerEventDecoder} and re-serialises to the same JSON,
 *       losing nothing.
 * </ul>
 */
class LedgerEventSchemaConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LedgerEventDecoder decoder = new LedgerEventDecoder();

  @Test
  void theTransferExampleCarriesExactlyTheSchemaFields() {
    assertExampleMatchesSchema("ledger-transfer-v1.schema.json", "ledger-transfer-v1.example.json");
  }

  @Test
  void theEntryExampleCarriesExactlyTheSchemaFields() {
    assertExampleMatchesSchema("ledger-entry-v1.schema.json", "ledger-entry-v1.example.json");
  }

  @Test
  void theExamplesAreConsistentWithTheCapturedCdcPayload() {
    JsonNode cdc = read("ledger-cdc-payload-0.17.4.example.json");
    JsonNode transfer = read("ledger-transfer-v1.example.json");
    JsonNode entry = read("ledger-entry-v1.example.json");

    assertThat(transfer.get("transferId").asText())
        .isEqualTo(cdc.get("transfer").get("id").asText());
    assertThat(transfer.get("amount").asText())
        .isEqualTo(cdc.get("transfer").get("amount").asText());
    assertThat(transfer.get("ledger").asInt()).isEqualTo(cdc.get("ledger").asInt());
    assertThat(transfer.get("debitAccountId").asText())
        .isEqualTo(cdc.get("debit_account").get("id").asText());
    assertThat(transfer.get("creditAccountId").asText())
        .isEqualTo(cdc.get("credit_account").get("id").asText());

    // The committed entry example is the debit leg.
    assertThat(entry.get("direction").asText()).isEqualTo("DEBIT");
    assertThat(entry.get("accountId").asText())
        .isEqualTo(cdc.get("debit_account").get("id").asText());
    assertThat(entry.get("debitsPosted").asText())
        .isEqualTo(cdc.get("debit_account").get("debits_posted").asText());
  }

  @Test
  void theTransferExampleRoundTripsThroughTheDecoderLosingNothing() {
    JsonNode json = read("ledger-transfer-v1.example.json");
    LedgerTransferEvent.Cash decoded =
        (LedgerTransferEvent.Cash) decoder.decodeTransfer(json.toString());

    assertThat(decoded.schemaVersion()).isEqualTo(json.get("schemaVersion").asText());
    assertThat(decoded.eventType().wireName()).isEqualTo(json.get("eventType").asText());
    assertThat(decoded.ledger()).isEqualTo(json.get("ledger").asLong());
    assertThat(decoded.timestamp()).isEqualTo(json.get("timestamp").asText());
    assertThat(decoded.transferId()).isEqualTo(json.get("transferId").asText());
    assertThat(Long.toString(decoded.amount().cents())).isEqualTo(json.get("amount").asText());
    assertThat(decoded.pendingId().orElse("0")).isEqualTo(json.get("pendingId").asText());
    assertThat(decoded.transferCode()).isEqualTo(json.get("transferCode").asInt());
    assertThat(decoded.transferFlags()).isEqualTo(json.get("transferFlags").asInt());
    assertThat(decoded.transferTimestamp()).isEqualTo(json.get("transferTimestamp").asText());
    assertThat(decoded.userData128()).isEqualTo(json.get("userData128").asText());
    assertThat(decoded.userData64()).isEqualTo(json.get("userData64").asText());
    assertThat(decoded.userData32()).isEqualTo(json.get("userData32").asLong());
    assertThat(decoded.debitAccountId()).isEqualTo(json.get("debitAccountId").asText());
    assertThat(decoded.creditAccountId()).isEqualTo(json.get("creditAccountId").asText());
    assertThat(decoded.debitAccountCode()).isEqualTo(json.get("debitAccountCode").asInt());
    assertThat(decoded.creditAccountCode()).isEqualTo(json.get("creditAccountCode").asInt());
  }

  @Test
  void theEntryExampleRoundTripsThroughTheDecoderLosingNothing() {
    JsonNode json = read("ledger-entry-v1.example.json");
    LedgerEntryEvent.Cash decoded = (LedgerEntryEvent.Cash) decoder.decodeEntry(json.toString());

    assertThat(decoded.schemaVersion()).isEqualTo(json.get("schemaVersion").asText());
    assertThat(decoded.eventType().wireName()).isEqualTo(json.get("eventType").asText());
    assertThat(decoded.ledger()).isEqualTo(json.get("ledger").asLong());
    assertThat(decoded.timestamp()).isEqualTo(json.get("timestamp").asText());
    assertThat(decoded.transferId()).isEqualTo(json.get("transferId").asText());
    assertThat(decoded.transferCode()).isEqualTo(json.get("transferCode").asInt());
    assertThat(decoded.transferTimestamp()).isEqualTo(json.get("transferTimestamp").asText());
    assertThat(Long.toString(decoded.amount().cents())).isEqualTo(json.get("amount").asText());
    assertThat(decoded.direction().name()).isEqualTo(json.get("direction").asText());
    assertThat(decoded.accountId()).isEqualTo(json.get("accountId").asText());
    assertThat(decoded.accountCode()).isEqualTo(json.get("accountCode").asInt());
    assertThat(decoded.accountFlags()).isEqualTo(json.get("accountFlags").asInt());
    assertThat(Long.toString(decoded.debitsPending().cents()))
        .isEqualTo(json.get("debitsPending").asText());
    assertThat(Long.toString(decoded.debitsPosted().cents()))
        .isEqualTo(json.get("debitsPosted").asText());
    assertThat(Long.toString(decoded.creditsPending().cents()))
        .isEqualTo(json.get("creditsPending").asText());
    assertThat(Long.toString(decoded.creditsPosted().cents()))
        .isEqualTo(json.get("creditsPosted").asText());
    assertThat(decoded.accountUserData128()).isEqualTo(json.get("accountUserData128").asText());
    assertThat(decoded.accountUserData64()).isEqualTo(json.get("accountUserData64").asText());
    assertThat(decoded.accountUserData32()).isEqualTo(json.get("accountUserData32").asLong());
    assertThat(decoded.pendingId().orElse("0")).isEqualTo(json.get("pendingId").asText());
  }

  private void assertExampleMatchesSchema(String schemaFile, String exampleFile) {
    JsonNode schema = read(schemaFile);
    JsonNode example = read(exampleFile);

    TreeSet<String> schemaProperties = new TreeSet<>();
    schema.get("properties").fieldNames().forEachRemaining(schemaProperties::add);

    TreeSet<String> requiredProperties = new TreeSet<>();
    schema.get("required").forEach(node -> requiredProperties.add(node.asText()));

    TreeSet<String> exampleFields = new TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);

    assertThat(requiredProperties)
        .as("every property in %s must be required", schemaFile)
        .isEqualTo(schemaProperties);
    assertThat(exampleFields)
        .as("%s must carry exactly the fields %s declares", exampleFile, schemaFile)
        .isEqualTo(schemaProperties);

    List<String> nonConforming = new ArrayList<>();
    example
        .fieldNames()
        .forEachRemaining(
            field -> {
              JsonNode spec = resolve(schema, schema.get("properties").get(field));
              if (!valueMatches(spec, example.get(field))) {
                nonConforming.add(field);
              }
            });
    assertThat(nonConforming)
        .as("fields in %s that do not match their schema type", exampleFile)
        .isEmpty();
  }

  /** Follows a one-level {@code $ref} into {@code $defs}; returns the node unchanged otherwise. */
  private static JsonNode resolve(JsonNode schema, JsonNode spec) {
    JsonNode ref = spec.get("$ref");
    if (ref == null) {
      return spec;
    }
    String name = ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
    return schema.get("$defs").get(name);
  }

  private static boolean valueMatches(JsonNode spec, JsonNode value) {
    if (spec.has("const")) {
      return value.asText().equals(spec.get("const").asText());
    }
    String type = spec.path("type").asText("");
    return switch (type) {
      case "string" ->
          value.isTextual()
              && (!spec.has("pattern") || value.asText().matches(spec.get("pattern").asText()));
      case "integer" -> value.isIntegralNumber();
      default -> true;
    };
  }

  private static JsonNode read(String name) {
    Path path = repositoryRoot().resolve("contracts/events").resolve(name);
    try {
      return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + name, e);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Path.of("").toAbsolutePath());
  }
}
