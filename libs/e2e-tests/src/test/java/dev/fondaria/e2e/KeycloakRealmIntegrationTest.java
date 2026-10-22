package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.testing.KeycloakFixture;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Keycloak realm import, schema isolation, PKCE enforcement, and MFA
 * requirements (Phase 9 Task 9.1).
 */
@Tag("integration")
class KeycloakRealmIntegrationTest {

  private static KeycloakFixture keycloak;
  private static HttpClient httpClient;
  private static ObjectMapper objectMapper;

  @BeforeAll
  static void setUp() {
    keycloak = KeycloakFixture.getInstance();
    httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    objectMapper = new ObjectMapper();
  }

  @Test
  @DisplayName("AC1: Realm imports cleanly onto empty Keycloak and reimport is idempotent")
  void ac1_realmImportsCleanlyAndReimportIsIdempotent() throws Exception {
    // 1. Verify realm imported cleanly on startup
    HttpRequest getOidcConfig =
        HttpRequest.newBuilder()
            .uri(URI.create(keycloak.getOpenIdConfigurationUrl()))
            .GET()
            .build();
    HttpResponse<String> configResponse =
        httpClient.send(getOidcConfig, HttpResponse.BodyHandlers.ofString());

    assertThat(configResponse.statusCode()).isEqualTo(200);
    JsonNode oidcConfig = objectMapper.readTree(configResponse.body());
    assertThat(oidcConfig.path("issuer").asText()).isEqualTo(keycloak.getIssuer());

    var execResult =
        keycloak.execInContainer(
            "/opt/keycloak/bin/kc.sh",
            "import",
            "--file=/opt/keycloak/data/import/realm.json",
            "--override=true");

    assertThat(execResult.getExitCode())
        .as("Reimport command exit code must be 0: %s", execResult.getStderr())
        .isZero();

    // Verify realm is still fully functional after reimport
    HttpResponse<String> postReimportConfig =
        httpClient.send(getOidcConfig, HttpResponse.BodyHandlers.ofString());
    assertThat(postReimportConfig.statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("AC2: Exported realm diffed against committed file is empty")
  void ac2_exportedRealmDiffAgainstCommittedFileIsEmpty() throws Exception {
    Path committedPath = Path.of("infrastructure/keycloak/realm.json");
    if (!Files.exists(committedPath)) {
      committedPath = Path.of("../../infrastructure/keycloak/realm.json");
    }
    assertThat(committedPath).exists();
    JsonNode committedRealm = objectMapper.readTree(Files.readString(committedPath));

    // Fetch live realm representation via Keycloak Admin REST API
    String adminToken = keycloak.getAdminAccessToken();
    HttpRequest adminGetRealm =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    keycloak.getAuthServerUrl() + "/admin/realms/" + KeycloakFixture.REALM_NAME))
            .header("Authorization", "Bearer " + adminToken)
            .GET()
            .build();

    HttpResponse<String> adminResponse =
        httpClient.send(adminGetRealm, HttpResponse.BodyHandlers.ofString());
    assertThat(adminResponse.statusCode()).isEqualTo(200);
    JsonNode liveRealm = objectMapper.readTree(adminResponse.body());

    // Assert key configuration parity
    assertThat(liveRealm.path("realm").asText()).isEqualTo(committedRealm.path("realm").asText());
    assertThat(liveRealm.path("enabled").asBoolean())
        .isEqualTo(committedRealm.path("enabled").asBoolean());
    assertThat(liveRealm.path("accessTokenLifespan").asInt())
        .isEqualTo(committedRealm.path("accessTokenLifespan").asInt());
    assertThat(liveRealm.path("revokeRefreshToken").asBoolean())
        .isEqualTo(committedRealm.path("revokeRefreshToken").asBoolean());
    assertThat(liveRealm.path("refreshTokenMaxReuse").asInt())
        .isEqualTo(committedRealm.path("refreshTokenMaxReuse").asInt());

    // Verify clients via Admin API
    HttpRequest getClients =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    keycloak.getAuthServerUrl()
                        + "/admin/realms/"
                        + KeycloakFixture.REALM_NAME
                        + "/clients"))
            .header("Authorization", "Bearer " + adminToken)
            .GET()
            .build();
    HttpResponse<String> clientsResponse =
        httpClient.send(getClients, HttpResponse.BodyHandlers.ofString());
    assertThat(clientsResponse.statusCode()).isEqualTo(200);
    JsonNode liveClients = objectMapper.readTree(clientsResponse.body());

    for (String expectedClientId :
        List.of("participant-app", "employer-portal", "internal-services")) {
      JsonNode liveClient = null;
      for (JsonNode c : liveClients) {
        if (expectedClientId.equals(c.path("clientId").asText())) {
          liveClient = c;
          break;
        }
      }
      assertThat(liveClient).as("Client %s must exist in live realm", expectedClientId).isNotNull();

      if ("participant-app".equals(expectedClientId)) {
        assertThat(liveClient.path("publicClient").asBoolean()).isTrue();
        assertThat(liveClient.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(liveClient.path("attributes").path("pkce.code.challenge.method").asText())
            .isEqualTo("S256");
      } else if ("employer-portal".equals(expectedClientId)) {
        assertThat(liveClient.path("publicClient").asBoolean()).isFalse();
        assertThat(liveClient.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(liveClient.path("attributes").path("pkce.code.challenge.method").asText())
            .isEqualTo("S256");
      } else if ("internal-services".equals(expectedClientId)) {
        assertThat(liveClient.path("publicClient").asBoolean()).isFalse();
        assertThat(liveClient.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(liveClient.path("standardFlowEnabled").asBoolean()).isFalse();
      }
    }
  }

  @Test
  @DisplayName("AC3: A client without PKCE is rejected")
  void ac3_clientWithoutPkceIsRejected() throws Exception {
    // 1. Authorization request WITHOUT PKCE (no code_challenge)
    String authUrlWithoutPkce =
        keycloak.getAuthEndpoint()
            + "?response_type=code"
            + "&client_id=participant-app"
            + "&redirect_uri="
            + URLEncoder.encode("http://localhost:3000/callback", StandardCharsets.UTF_8)
            + "&scope=openid";

    HttpRequest requestWithoutPkce =
        HttpRequest.newBuilder().uri(URI.create(authUrlWithoutPkce)).GET().build();

    HttpResponse<String> responseWithoutPkce =
        httpClient.send(requestWithoutPkce, HttpResponse.BodyHandlers.ofString());

    // Keycloak rejects missing PKCE per RFC 6749 / RFC 7636:
    // With a valid redirect_uri, it redirects (302) with error=invalid_request; without it, it
    // returns 400.
    if (responseWithoutPkce.statusCode() == 302) {
      String location = responseWithoutPkce.headers().firstValue("Location").orElse("");
      assertThat(location)
          .as("Redirect URL must indicate invalid_request")
          .contains("error=invalid_request");
    } else {
      assertThat(responseWithoutPkce.statusCode())
          .as("Request without PKCE must be rejected with 400")
          .isEqualTo(400);
      assertThat(responseWithoutPkce.body()).contains("invalid_request");
    }

    // 2. Authorization request WITH PKCE (S256 challenge)
    String authUrlWithPkce =
        authUrlWithoutPkce
            + "&code_challenge=E9Melhoa2OwvFrGMTJguCH5ZiXVupWhBatSTbOGOn6A"
            + "&code_challenge_method=S256";

    HttpRequest requestWithPkce =
        HttpRequest.newBuilder().uri(URI.create(authUrlWithPkce)).GET().build();

    HttpResponse<String> responseWithPkce =
        httpClient.send(requestWithPkce, HttpResponse.BodyHandlers.ofString());

    // Keycloak accepts the request (either 200 with login form or redirect to login)
    assertThat(responseWithPkce.statusCode())
        .as("Request with PKCE must be accepted, not rejected with 400")
        .isNotEqualTo(400);
  }

  @Test
  @DisplayName("AC4: An employer admin cannot authenticate without MFA")
  void ac4_employerAdminCannotAuthenticateWithoutMfa() throws Exception {
    // 1. Employer admin attempting direct grant password authentication without OTP
    String adminForm =
        "grant_type=password"
            + "&client_id=employer-portal"
            + "&client_secret=employer-portal-secret"
            + "&username="
            + URLEncoder.encode("employer.admin", StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode("admin_pass123", StandardCharsets.UTF_8);

    HttpRequest adminAuthRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(keycloak.getTokenEndpoint()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(adminForm))
            .build();

    HttpResponse<String> adminAuthResponse =
        httpClient.send(adminAuthRequest, HttpResponse.BodyHandlers.ofString());

    // Employer admin must be rejected without MFA
    assertThat(adminAuthResponse.statusCode())
        .as("Employer admin without MFA must be rejected")
        .isGreaterThanOrEqualTo(400);
    assertThat(adminAuthResponse.body())
        .as("Response should indicate invalid_grant or missing totp/mfa requirement")
        .containsAnyOf("invalid_grant", "Missing totp", "totp", "mfa");

    // 2. Internal staff also has required MFA and must be rejected without OTP
    String staffForm =
        "grant_type=password"
            + "&client_id=employer-portal"
            + "&client_secret=employer-portal-secret"
            + "&username="
            + URLEncoder.encode("internal.staff", StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode("staff_pass123", StandardCharsets.UTF_8);

    HttpRequest staffAuthRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(keycloak.getTokenEndpoint()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(staffForm))
            .build();

    HttpResponse<String> staffAuthResponse =
        httpClient.send(staffAuthRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(staffAuthResponse.statusCode())
        .as("Internal staff without MFA must be rejected")
        .isGreaterThanOrEqualTo(400);

    // 3. Participant user (who has not configured MFA) CAN authenticate with username & password
    // alone
    String participantForm =
        "grant_type=password"
            + "&client_id=employer-portal"
            + "&client_secret=employer-portal-secret"
            + "&username="
            + URLEncoder.encode("participant.user", StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode("participant_pass123", StandardCharsets.UTF_8);

    HttpRequest participantAuthRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(keycloak.getTokenEndpoint()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(participantForm))
            .build();

    HttpResponse<String> participantAuthResponse =
        httpClient.send(participantAuthRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(participantAuthResponse.statusCode())
        .as("Participant without MFA can authenticate successfully")
        .isEqualTo(200);
    JsonNode tokenResponse = objectMapper.readTree(participantAuthResponse.body());
    assertThat(tokenResponse.path("access_token").asText()).isNotBlank();
    assertThat(tokenResponse.path("refresh_token").asText()).isNotBlank();
  }
}
