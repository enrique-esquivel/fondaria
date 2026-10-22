package dev.fondaria.demo.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonRoundTripTest {

  @Test
  void writesAndParsesNestedStructures() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("name", "Acme \"S.A.\"");
    inner.put("count", 3L);
    inner.put("active", true);
    inner.put("missing", null);
    inner.put("tags", List.of("a", "b"));

    Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("items", List.of(inner));
    outer.put("total", 1000L);

    String json = Json.write(outer);
    Map<String, Object> parsed = JsonParser.parseObject(json);

    assertThat(parsed.get("total")).isEqualTo(1000L);
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) parsed.get("items");
    @SuppressWarnings("unchecked")
    Map<String, Object> firstItem = (Map<String, Object>) items.get(0);
    assertThat(firstItem.get("name")).isEqualTo("Acme \"S.A.\"");
    assertThat(firstItem.get("count")).isEqualTo(3L);
    assertThat(firstItem.get("active")).isEqualTo(true);
    assertThat(firstItem.get("missing")).isNull();
    assertThat(firstItem.get("tags")).isEqualTo(List.of("a", "b"));
  }

  @Test
  void parsesNegativeAndFractionalNumbers() {
    Map<String, Object> parsed = JsonParser.parseObject("{\"a\":-42,\"b\":-1.5,\"c\":0}");
    assertThat(parsed.get("a")).isEqualTo(-42L);
    assertThat(parsed.get("b")).isEqualTo(-1.5);
    assertThat(parsed.get("c")).isEqualTo(0L);
  }

  @Test
  void atNavigatesDottedPaths() {
    Map<String, Object> parsed =
        JsonParser.parseObject("{\"parts\":{\"allottedParts\":3,\"partCount\":5}}");
    assertThat(JsonParser.longAt(parsed, "parts.allottedParts")).isEqualTo(3L);
    assertThat(JsonParser.longAt(parsed, "parts.partCount")).isEqualTo(5L);
  }

  @Test
  void missingPathThrows() {
    Map<String, Object> parsed = JsonParser.parseObject("{}");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> JsonParser.longAt(parsed, "parts.allottedParts"));
  }
}
