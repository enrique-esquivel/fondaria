package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates infrastructure/keycloak/realm.json declarations (Task 9.1). Runs during make verify
 * with no container dependencies.
 */
class KeycloakRealmValidationTest {

  private static JsonNode realm;

  @BeforeAll
  static void loadRealm() throws Exception {
    Path path = Path.of("infrastructure/keycloak/realm.json");
    if (!Files.exists(path)) {
      path = Path.of("../../infrastructure/keycloak/realm.json");
    }
    assertThat(path).exists();
    ObjectMapper mapper = new ObjectMapper();
    realm = mapper.readTree(Files.readString(path));
  }

  @Test
  @DisplayName("Realm identity and basic configuration are valid")
  void realmBasics() {
    assertThat(realm.get("realm").asText()).isEqualTo("fondaria");
    assertThat(realm.get("enabled").asBoolean()).isTrue();
    assertThat(realm.get("sslRequired").asText()).isEqualTo("external");
  }

  @Test
  @DisplayName("Token lifespans enforce short access tokens and refresh token rotation")
  void tokenLifespansAndRotation() {
    assertThat(realm.get("accessTokenLifespan").asInt())
        .isLessThanOrEqualTo(300); // 5 minutes or less
    assertThat(realm.get("revokeRefreshToken").asBoolean()).isTrue();
    assertThat(realm.get("refreshTokenMaxReuse").asInt()).isZero();
  }

  @Test
  @DisplayName("All three required client types exist and match requirements")
  void clientTypes() {
    JsonNode clients = realm.get("clients");
    assertThat(clients).isNotNull();

    JsonNode participantApp = findClient(clients, "participant-app");
    assertThat(participantApp).isNotNull();
    assertThat(participantApp.get("publicClient").asBoolean()).isTrue();
    assertThat(participantApp.get("standardFlowEnabled").asBoolean()).isTrue();
    assertThat(participantApp.path("attributes").path("pkce.code.challenge.method").asText())
        .isEqualTo("S256");

    JsonNode employerPortal = findClient(clients, "employer-portal");
    assertThat(employerPortal).isNotNull();
    assertThat(employerPortal.get("publicClient").asBoolean()).isFalse();
    assertThat(employerPortal.get("standardFlowEnabled").asBoolean()).isTrue();
    assertThat(employerPortal.path("attributes").path("pkce.code.challenge.method").asText())
        .isEqualTo("S256");
    assertThat(employerPortal.path("secret").asText()).isNotBlank();

    JsonNode internalServices = findClient(clients, "internal-services");
    assertThat(internalServices).isNotNull();
    assertThat(internalServices.get("publicClient").asBoolean()).isFalse();
    assertThat(internalServices.get("serviceAccountsEnabled").asBoolean()).isTrue();
    assertThat(internalServices.get("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(internalServices.get("directAccessGrantsEnabled").asBoolean()).isFalse();
    assertThat(internalServices.path("secret").asText()).isNotBlank();
  }

  @Test
  @DisplayName("Roles for the three populations exist")
  void rolesDefined() {
    JsonNode realmRoles = realm.path("roles").path("realm");
    assertThat(realmRoles.isArray()).isTrue();

    List<String> roleNames = new ArrayList<>();
    realmRoles.forEach(r -> roleNames.add(r.path("name").asText()));

    assertThat(roleNames)
        .contains(
            "participant",
            "employer-admin",
            "internal-staff",
            "internal-support",
            "internal-operations",
            "internal-approver");
  }

  @Test
  @DisplayName("Conditional MFA is configured for employer-admin and internal-staff")
  void mfaConfiguration() {
    JsonNode configs = realm.get("authenticatorConfig");
    assertThat(configs).isNotNull();

    boolean hasRoleMfaConfig = false;
    for (JsonNode config : configs) {
      if ("require-mfa-roles".equals(config.path("alias").asText())) {
        String role = config.path("config").path("condUserRole").asText();
        assertThat(role).isEqualTo("mfa-enforced");
        hasRoleMfaConfig = true;
      }
    }
    assertThat(hasRoleMfaConfig).as("require-mfa-roles authenticatorConfig must exist").isTrue();

    JsonNode flows = realm.get("authenticationFlows");
    assertThat(flows).isNotNull();
    List<String> flowAliases = new ArrayList<>();
    flows.forEach(f -> flowAliases.add(f.path("alias").asText()));

    assertThat(flowAliases)
        .contains(
            "browser",
            "forms",
            "Browser - Staff and Admin OTP",
            "Browser - Conditional OTP",
            "direct grant",
            "Direct Grant - Staff and Admin OTP",
            "Direct Grant - Conditional OTP");
  }

  @Test
  @DisplayName(
      "Every client carries an explicit audience mapper for Task 10.2's edge JWT validation")
  void audienceMapperPresentOnEveryClient() {
    // securitypolicy-jwt.yaml (Task 10.2) checks `aud` against a fixed value at the edge.
    // Keycloak does not reliably place a caller's own client_id into `aud` without an explicit
    // mapper (that is `azp`, not `aud`) -- each client must carry its own, not inherit one from a
    // realm-level default scope (see securitypolicy-jwt.yaml's own comment for why touching
    // top-level clientScopes/defaultDefaultClientScopes was rejected as too high-risk for this
    // realm: every client's `defaultClientScopes` already references Keycloak's built-in
    // "roles"/"profile"/etc. by name without this file ever defining them, meaning Keycloak
    // auto-populates those built-ins on import -- redefining clientScopes here would replace
    // them, not add to them, silently breaking CallerIdentityParser's `realm_access.roles` read).
    JsonNode clients = realm.get("clients");
    for (String clientId : List.of("participant-app", "employer-portal", "internal-services")) {
      JsonNode client = findClient(clients, clientId);
      assertThat(client).as("client %s", clientId).isNotNull();

      JsonNode mappers = client.get("protocolMappers");
      assertThat(mappers).as("%s must define protocolMappers", clientId).isNotNull();

      JsonNode audienceMapper = null;
      for (JsonNode mapper : mappers) {
        if ("oidc-audience-mapper".equals(mapper.path("protocolMapper").asText())) {
          audienceMapper = mapper;
        }
      }
      assertThat(audienceMapper)
          .as("%s must have an oidc-audience-mapper protocol mapper", clientId)
          .isNotNull();
      assertThat(audienceMapper.path("config").path("included.custom.audience").asText())
          .as("%s audience mapper value", clientId)
          .isEqualTo("fondaria-api");
      assertThat(audienceMapper.path("config").path("access.token.claim").asText())
          .as("%s audience mapper must apply to access tokens", clientId)
          .isEqualTo("true");
    }
  }

  private JsonNode findClient(JsonNode clients, String clientId) {
    for (JsonNode client : clients) {
      if (clientId.equals(client.path("clientId").asText())) {
        return client;
      }
    }
    return null;
  }
}
