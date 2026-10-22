package dev.fondaria.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Parses {@link CallerIdentity} from standard JWT Bearer tokens or claims JSON without requiring
 * external security dependencies.
 */
public final class CallerIdentityParser {

  private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

  private CallerIdentityParser() {}

  public static Optional<CallerIdentity> parseBearerHeader(String authorizationHeader) {
    return parseBearerHeader(authorizationHeader, DEFAULT_MAPPER);
  }

  public static Optional<CallerIdentity> parseBearerHeader(
      String authorizationHeader, ObjectMapper mapper) {
    if (authorizationHeader == null
        || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = authorizationHeader.substring(7).trim();
    if (token.isEmpty()) {
      return Optional.empty();
    }
    return parseToken(token, mapper);
  }

  public static Optional<CallerIdentity> parseToken(String token, ObjectMapper mapper) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      String payloadJson;
      int firstDot = token.indexOf('.');
      if (firstDot > 0) {
        int secondDot = token.indexOf('.', firstDot + 1);
        String payloadBase64 =
            secondDot > 0
                ? token.substring(firstDot + 1, secondDot)
                : token.substring(firstDot + 1);
        byte[] decoded = Base64.getUrlDecoder().decode(payloadBase64);
        payloadJson = new String(decoded, StandardCharsets.UTF_8);
      } else {
        // Raw JSON passed in testing
        payloadJson = token;
      }
      return Optional.of(parseClaims(payloadJson, mapper));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static CallerIdentity parseClaims(String claimsJson, ObjectMapper mapper) {
    try {
      JsonNode root = mapper.readTree(claimsJson);
      String subject = root.hasNonNull("sub") ? root.get("sub").asText() : "unknown";

      Set<String> roles = new HashSet<>();
      if (root.has("realm_access") && root.get("realm_access").has("roles")) {
        for (JsonNode r : root.get("realm_access").get("roles")) {
          roles.add(r.asText());
        }
      }
      if (root.has("roles") && root.get("roles").isArray()) {
        for (JsonNode r : root.get("roles")) {
          roles.add(r.asText());
        }
      }

      Optional<OrganizationId> orgId = Optional.empty();
      String rawOrg = null;
      if (root.hasNonNull("org_id")) {
        rawOrg = root.get("org_id").asText();
      } else if (root.hasNonNull("organization_id")) {
        rawOrg = root.get("organization_id").asText();
      } else if (root.hasNonNull("organizationId")) {
        rawOrg = root.get("organizationId").asText();
      } else if (root.has("attributes") && root.get("attributes").hasNonNull("organization_id")) {
        JsonNode attr = root.get("attributes").get("organization_id");
        rawOrg = attr.isArray() && !attr.isEmpty() ? attr.get(0).asText() : attr.asText();
      }
      if (rawOrg != null && !rawOrg.isBlank()) {
        orgId = Optional.of(OrganizationId.parse(rawOrg));
      }

      Optional<ParticipantId> participantId = Optional.empty();
      String rawPart = null;
      if (root.hasNonNull("participant_id")) {
        rawPart = root.get("participant_id").asText();
      } else if (root.hasNonNull("participantId")) {
        rawPart = root.get("participantId").asText();
      } else if (root.has("attributes") && root.get("attributes").hasNonNull("participant_id")) {
        JsonNode attr = root.get("attributes").get("participant_id");
        rawPart = attr.isArray() && !attr.isEmpty() ? attr.get(0).asText() : attr.asText();
      }
      if (rawPart == null || rawPart.isBlank()) {
        rawPart = subject;
      }
      try {
        participantId = Optional.of(ParticipantId.parse(rawPart));
      } catch (IllegalArgumentException ignored) {
        // Not a UUID participant id
      }

      UserPopulation population = UserPopulation.PARTICIPANT;
      if (root.hasNonNull("population")) {
        population = UserPopulation.valueOf(root.get("population").asText().toUpperCase());
      } else if (roles.contains("internal-service") || roles.contains("internal-services")) {
        population = UserPopulation.INTERNAL_SERVICE;
      } else if (roles.contains("employer-admin") || orgId.isPresent()) {
        population = UserPopulation.EMPLOYER_ADMIN;
      } else if (roles.contains("internal-staff")
          || roles.contains("internal-support")
          || roles.contains("internal-operations")
          || roles.contains("internal-approver")) {
        population = UserPopulation.INTERNAL_STAFF;
      }

      return new CallerIdentity(subject, population, roles, orgId, participantId);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Failed to parse caller identity claims: " + e.getMessage(), e);
    }
  }
}
