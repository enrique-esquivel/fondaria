package dev.fondaria.demo.manifest;

import dev.fondaria.demo.json.Json;
import dev.fondaria.demo.json.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `demo/out/state.json` -- the cursor every day-cycling command reads and advances, since each
 * `demo-tools` invocation is a fresh JVM process with no memory of the last one. Three cursors:
 * which payroll period `contributions` labels next, which business date `valuations`/`advance` move
 * to next, and where in the scripted NAV-move list `valuations` is (`phase-D-demo.md` Task D.2).
 *
 * @param periodIndex how many payroll periods `contributions` has declared so far
 * @param navSeriesIndex how many scripted NAV moves `valuations` has applied so far, mod the series
 *     length -- the series itself is {@link NavSeries#SCRIPTED_MOVES}
 * @param currentBusinessDate the simulated business date the platform is currently at, advanced by
 *     {@code advance}
 */
public record DemoState(int periodIndex, int navSeriesIndex, LocalDate currentBusinessDate) {

  /** A fresh demo starts its payroll and NAV cursors at zero, dated at real today. */
  public static DemoState initial(LocalDate today) {
    return new DemoState(0, 0, today);
  }

  public static DemoState readFrom(Path path, LocalDate defaultToday) {
    if (!Files.exists(path)) {
      return initial(defaultToday);
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    Map<String, Object> json = JsonParser.parseObject(content);
    return new DemoState(
        ((Number) json.get("periodIndex")).intValue(),
        ((Number) json.get("navSeriesIndex")).intValue(),
        LocalDate.parse((String) json.get("currentBusinessDate")));
  }

  public void writeTo(Path path) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("periodIndex", periodIndex);
    json.put("navSeriesIndex", navSeriesIndex);
    json.put("currentBusinessDate", currentBusinessDate.toString());
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, Json.write(json), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }

  public DemoState withPeriodIndex(int newPeriodIndex) {
    return new DemoState(newPeriodIndex, navSeriesIndex, currentBusinessDate);
  }

  public DemoState withNavSeriesIndex(int newNavSeriesIndex) {
    return new DemoState(periodIndex, newNavSeriesIndex, currentBusinessDate);
  }

  public DemoState withCurrentBusinessDate(LocalDate newDate) {
    return new DemoState(periodIndex, navSeriesIndex, newDate);
  }
}
