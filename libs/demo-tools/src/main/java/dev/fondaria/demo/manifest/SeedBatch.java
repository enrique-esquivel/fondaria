package dev.fondaria.demo.manifest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The first contribution batch {@code seed} submitted for an organization, as {@code
 * demo/out/manifest.json} records it -- enough for a resumed {@code seed} run to count this
 * organization's cash in its fund's opening baseline and to wait for it to allot, without
 * re-deriving either from the platform.
 *
 * @param dealingDate the date the platform said this batch deals on; empty when it was {@code
 *     REJECTED}, which deals no cash at all
 */
public record SeedBatch(String batchId, Optional<LocalDate> dealingDate, long declaredTotalCents) {

  /** Whether this batch's cash turns into units on {@code date}. */
  public boolean dealsOn(LocalDate date) {
    return dealingDate.map(date::equals).orElse(false);
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("batchId", batchId);
    json.put("dealingDate", dealingDate.map(LocalDate::toString).orElse(null));
    json.put("declaredTotalCents", declaredTotalCents);
    return json;
  }

  static SeedBatch fromJson(Map<String, Object> json) {
    return new SeedBatch(
        (String) json.get("batchId"),
        Optional.ofNullable((String) json.get("dealingDate")).map(LocalDate::parse),
        ((Number) json.get("declaredTotalCents")).longValue());
  }
}
