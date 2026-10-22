package dev.fondaria.demo.valuation;

import dev.fondaria.demo.json.Json;
import dev.fondaria.demo.json.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * `demo/out/nav-series.json` -- an append-only record of every valuation this module has sent, one
 * entry per fund per date (`phase-D-demo.md` Task D.2: "records what was sent"). Also how {@code
 * valuations} finds each fund's previous reported value: the last entry in the log for that fund,
 * never re-derived from the platform, since this module submits by reference and never reads a
 * valuation back.
 */
public final class NavSeriesLog {

  public record Entry(
      String date, String fundId, Long moveScaled, long reportedValueCents, int navSeriesIndex) {

    Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("date", date);
      json.put("fundId", fundId);
      json.put("moveScaled", moveScaled);
      json.put("reportedValueCents", reportedValueCents);
      json.put("navSeriesIndex", navSeriesIndex);
      return json;
    }

    static Entry fromJson(Map<String, Object> json) {
      Object move = json.get("moveScaled");
      return new Entry(
          (String) json.get("date"),
          (String) json.get("fundId"),
          move == null ? null : ((Number) move).longValue(),
          ((Number) json.get("reportedValueCents")).longValue(),
          ((Number) json.get("navSeriesIndex")).intValue());
    }
  }

  private final List<Entry> entries;

  private NavSeriesLog(List<Entry> entries) {
    this.entries = entries;
  }

  @SuppressWarnings("unchecked")
  public static NavSeriesLog readFrom(Path path) {
    if (!Files.exists(path)) {
      return new NavSeriesLog(new ArrayList<>());
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    List<Entry> entries = new ArrayList<>();
    for (Object raw : (List<Object>) JsonParser.parse(content)) {
      entries.add(Entry.fromJson((Map<String, Object>) raw));
    }
    return new NavSeriesLog(entries);
  }

  public void writeTo(Path path) {
    List<Map<String, Object>> json = entries.stream().map(Entry::toJson).toList();
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, Json.write(json), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }

  public void append(Entry entry) {
    entries.add(entry);
  }

  public Optional<Long> lastReportedValueCents(String fundId) {
    for (int i = entries.size() - 1; i >= 0; i--) {
      if (entries.get(i).fundId().equals(fundId)) {
        return Optional.of(entries.get(i).reportedValueCents());
      }
    }
    return Optional.empty();
  }
}
