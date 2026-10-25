package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The Task 4.4 CI guard: a {@code v1} event schema may gain a field (additive), but it may never
 * lose a field or change a field's type. A backwards-incompatible change is {@code v2} on a new
 * topic, not an edit to a {@code v1} schema.
 *
 * <p>The frozen contract lives in this file, right next to the assertion that enforces it — the
 * same shape as {@code NoIncrByFloatTest} embedding its rule in code rather than a resource
 * somebody deletes. To make a breaking change you must edit both the schema in {@code
 * contracts/events} and the frozen map below, which is exactly the deliberate, reviewed act the
 * rule is meant to force.
 *
 * <p>Runs under plain {@code make verify} with no container, which is what makes it a CI check
 * rather than a convention.
 */
class LedgerEventSchemaCompatibilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String UINT = "string~^(0|[1-9][0-9]*)$";
  private static final String UINT16 = "integer[0,65535]";
  private static final String UINT32 = "integer[0,4294967295]";
  private static final String LEDGER = "integer[0,4294967295]";
  private static final String EVENT_TYPE_ENUM =
      "enum:single_phase|two_phase_pending|two_phase_posted|two_phase_voided|two_phase_expired";

  /** The frozen {@code ledger-transfer-v1} field set. Additions here are additive; edits are v2. */
  private static final Map<String, String> FROZEN_TRANSFER_V1 = new LinkedHashMap<>();

  /** The frozen {@code ledger-entry-v1} field set. */
  private static final Map<String, String> FROZEN_ENTRY_V1 = new LinkedHashMap<>();

  static {
    FROZEN_TRANSFER_V1.put("schemaVersion", "const=v1");
    FROZEN_TRANSFER_V1.put("eventType", EVENT_TYPE_ENUM);
    FROZEN_TRANSFER_V1.put("ledger", LEDGER);
    FROZEN_TRANSFER_V1.put("timestamp", UINT);
    FROZEN_TRANSFER_V1.put("transferId", UINT);
    FROZEN_TRANSFER_V1.put("amount", UINT);
    FROZEN_TRANSFER_V1.put("pendingId", UINT);
    FROZEN_TRANSFER_V1.put("transferCode", UINT16);
    FROZEN_TRANSFER_V1.put("transferFlags", UINT16);
    FROZEN_TRANSFER_V1.put("transferTimestamp", UINT);
    FROZEN_TRANSFER_V1.put("userData128", UINT);
    FROZEN_TRANSFER_V1.put("userData64", UINT);
    FROZEN_TRANSFER_V1.put("userData32", UINT32);
    FROZEN_TRANSFER_V1.put("debitAccountId", UINT);
    FROZEN_TRANSFER_V1.put("creditAccountId", UINT);
    FROZEN_TRANSFER_V1.put("debitAccountCode", UINT16);
    FROZEN_TRANSFER_V1.put("creditAccountCode", UINT16);

    FROZEN_ENTRY_V1.put("schemaVersion", "const=v1");
    FROZEN_ENTRY_V1.put("eventType", EVENT_TYPE_ENUM);
    FROZEN_ENTRY_V1.put("ledger", LEDGER);
    FROZEN_ENTRY_V1.put("timestamp", UINT);
    FROZEN_ENTRY_V1.put("transferId", UINT);
    FROZEN_ENTRY_V1.put("transferCode", UINT16);
    FROZEN_ENTRY_V1.put("transferTimestamp", UINT);
    FROZEN_ENTRY_V1.put("amount", UINT);
    FROZEN_ENTRY_V1.put("direction", "enum:DEBIT|CREDIT");
    FROZEN_ENTRY_V1.put("accountId", UINT);
    FROZEN_ENTRY_V1.put("accountCode", UINT16);
    FROZEN_ENTRY_V1.put("accountFlags", UINT16);
    FROZEN_ENTRY_V1.put("debitsPending", UINT);
    FROZEN_ENTRY_V1.put("debitsPosted", UINT);
    FROZEN_ENTRY_V1.put("creditsPending", UINT);
    FROZEN_ENTRY_V1.put("creditsPosted", UINT);
    FROZEN_ENTRY_V1.put("accountUserData128", UINT);
    FROZEN_ENTRY_V1.put("accountUserData64", UINT);
    FROZEN_ENTRY_V1.put("accountUserData32", UINT32);
    FROZEN_ENTRY_V1.put("pendingId", UINT);
  }

  @Test
  void ledgerTransferV1IsStillBackwardsCompatible() {
    assertStillCompatible("ledger-transfer-v1.schema.json", FROZEN_TRANSFER_V1);
  }

  @Test
  void ledgerEntryV1IsStillBackwardsCompatible() {
    assertStillCompatible("ledger-entry-v1.schema.json", FROZEN_ENTRY_V1);
  }

  private void assertStillCompatible(String schemaFile, Map<String, String> frozen) {
    JsonNode schema = read(schemaFile);
    JsonNode properties = schema.get("properties");

    List<String> breaks = new ArrayList<>();
    for (Map.Entry<String, String> frozenField : frozen.entrySet()) {
      String name = frozenField.getKey();
      JsonNode spec = properties == null ? null : properties.get(name);
      if (spec == null) {
        breaks.add("field '" + name + "' was removed");
        continue;
      }
      String live = descriptor(schema, spec);
      if (!compatible(frozenField.getValue(), live)) {
        breaks.add(
            "field '" + name + "' changed type: was " + frozenField.getValue() + ", is " + live);
      }
    }

    assertThat(breaks)
        .withFailMessage(
            "%s is no longer backwards compatible with its frozen v1 contract:%n  %s%n"
                + "A field removal or type change is a new version (v2) on a new topic, never an edit "
                + "to a v1 schema. If this change really is additive, add the new field to the frozen "
                + "map in this test.",
            schemaFile, String.join("\n  ", breaks))
        .isEmpty();
  }

  /**
   * Frozen vs live descriptors: equal for scalars/const, subset-of for enums (a new member is
   * additive).
   */
  private static boolean compatible(String frozen, String live) {
    if (frozen.startsWith("enum:") && live.startsWith("enum:")) {
      TreeSet<String> frozenMembers = new TreeSet<>(List.of(frozen.substring(5).split("\\|")));
      TreeSet<String> liveMembers = new TreeSet<>(List.of(live.substring(5).split("\\|")));
      return liveMembers.containsAll(frozenMembers);
    }
    return frozen.equals(live);
  }

  private static String descriptor(JsonNode schema, JsonNode spec) {
    JsonNode resolved = spec;
    JsonNode ref = spec.get("$ref");
    if (ref != null) {
      String name = ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
      resolved = schema.get("$defs").get(name);
    }
    if (resolved.has("const")) {
      return "const=" + resolved.get("const").asText();
    }
    String type = resolved.path("type").asText("");
    if ("string".equals(type)) {
      if (resolved.has("enum")) {
        TreeSet<String> values = new TreeSet<>();
        resolved.get("enum").forEach(node -> values.add(node.asText()));
        return "enum:" + String.join("|", values);
      }
      if (resolved.has("pattern")) {
        return "string~" + resolved.get("pattern").asText();
      }
      return "string";
    }
    if ("integer".equals(type)) {
      return "integer["
          + resolved.path("minimum").asText("")
          + ","
          + resolved.path("maximum").asText("")
          + "]";
    }
    return type;
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
      if (Files.exists(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Path.of("").toAbsolutePath());
  }
}
