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
 * `demo/out/fund-flows.json` -- cash awaiting allotment per fund per dealing date, keyed by {@code
 * fundId + "|" + dealingDate}. {@code contributions} and {@code onboard} add to it, keyed by the
 * batch's own {@code dealingDate} rather than the date it was submitted on -- a batch with a
 * non-zero dealing lag deals, and therefore turns into units, on a later date than the one it was
 * declared for, and the fund's pool only actually grows on the date the units appear. {@code
 * valuations} drains every entry for the fund dealing strictly before the date it is valuing: cash
 * is invested only after it is allotted (row 26h), so a contribution dealing on T is in the value
 * reported for the first date after T's strike, never in T's. A separate small file rather than a
 * section of {@code state.json} because it is genuinely a different kind of state -- an accumulator
 * drained on read, not a cursor advanced on write.
 */
public final class FundFlows {

  private final Map<String, Long> pendingCentsByFundAndDate;

  private FundFlows(Map<String, Long> pendingCentsByFundAndDate) {
    this.pendingCentsByFundAndDate = pendingCentsByFundAndDate;
  }

  public static FundFlows readFrom(Path path) {
    if (!Files.exists(path)) {
      return new FundFlows(new LinkedHashMap<>());
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    Map<String, Object> raw = JsonParser.parseObject(content);
    Map<String, Long> pending = new LinkedHashMap<>();
    raw.forEach((key, value) -> pending.put(key, ((Number) value).longValue()));
    return new FundFlows(pending);
  }

  public void writeTo(Path path) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, Json.write(pendingCentsByFundAndDate), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }

  public void add(String fundId, LocalDate dealingDate, long cents) {
    pendingCentsByFundAndDate.merge(key(fundId, dealingDate), cents, Long::sum);
  }

  /**
   * Reads and zeroes this fund's pending contributions dealing strictly before {@code date}: those
   * already allotted by an earlier strike, and so safe to include in the value reported for {@code
   * date}. Contributions dealing on {@code date} itself stay pending.
   */
  public long drainDealtBefore(String fundId, LocalDate date) {
    String prefix = fundId + "|";
    long total = 0L;
    for (Map.Entry<String, Long> entry : pendingCentsByFundAndDate.entrySet()) {
      if (entry.getKey().startsWith(prefix)
          && LocalDate.parse(entry.getKey().substring(prefix.length())).isBefore(date)) {
        total += entry.getValue();
        entry.setValue(0L);
      }
    }
    return total;
  }

  private static String key(String fundId, LocalDate date) {
    return fundId + "|" + date;
  }
}
