package dev.fondaria.demo.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** One employer `seed` registered and enrolled, as `demo/out/manifest.json` records it. */
public record OrganizationRecord(
    String organizationId,
    String legalName,
    String planId,
    String fundId,
    int employeeCount,
    List<HeroParticipant> heroes) {

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("organizationId", organizationId);
    json.put("legalName", legalName);
    json.put("planId", planId);
    json.put("fundId", fundId);
    json.put("employeeCount", employeeCount);
    json.put("heroes", heroes.stream().map(HeroParticipant::toJson).collect(Collectors.toList()));
    return json;
  }

  @SuppressWarnings("unchecked")
  static OrganizationRecord fromJson(Map<String, Object> json) {
    List<HeroParticipant> heroes = new ArrayList<>();
    for (Object hero : (List<Object>) json.get("heroes")) {
      heroes.add(HeroParticipant.fromJson((Map<String, Object>) hero));
    }
    return new OrganizationRecord(
        (String) json.get("organizationId"),
        (String) json.get("legalName"),
        (String) json.get("planId"),
        (String) json.get("fundId"),
        ((Number) json.get("employeeCount")).intValue(),
        heroes);
  }
}
