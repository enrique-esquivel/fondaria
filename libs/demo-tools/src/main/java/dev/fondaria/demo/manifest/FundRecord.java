package dev.fondaria.demo.manifest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fund `seed` registered, as `demo/out/manifest.json` records it.
 *
 * @param launchDate the {@code effectiveFrom}/{@code launchDate} this fund was originally
 *     registered with -- replayed unchanged on a resumed run's idempotent re-registration.
 *     Registering the same {@code (fundId, version)} with a different {@code effectiveFrom} (e.g.
 *     "today" recomputed on a later calendar day) is a different body under the same key, which the
 *     platform refuses with {@code 409} rather than silently accepting.
 */
public record FundRecord(
    String fundId, long unitLedgerId, String vehicleType, String name, LocalDate launchDate) {

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("fundId", fundId);
    json.put("unitLedgerId", unitLedgerId);
    json.put("vehicleType", vehicleType);
    json.put("name", name);
    json.put("launchDate", launchDate.toString());
    return json;
  }

  static FundRecord fromJson(Map<String, Object> json) {
    return new FundRecord(
        (String) json.get("fundId"),
        ((Number) json.get("unitLedgerId")).longValue(),
        (String) json.get("vehicleType"),
        (String) json.get("name"),
        LocalDate.parse((String) json.get("launchDate")));
  }
}
