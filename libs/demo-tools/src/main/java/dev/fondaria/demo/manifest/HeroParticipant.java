package dev.fondaria.demo.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One named participant per organization, tracked across every later command by their {@code
 * participantId}.
 */
public record HeroParticipant(String internalRef, String payrollRef, String participantId) {

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("internalRef", internalRef);
    json.put("payrollRef", payrollRef);
    json.put("participantId", participantId);
    return json;
  }

  static HeroParticipant fromJson(Map<String, Object> json) {
    return new HeroParticipant(
        (String) json.get("internalRef"),
        (String) json.get("payrollRef"),
        (String) json.get("participantId"));
  }
}
