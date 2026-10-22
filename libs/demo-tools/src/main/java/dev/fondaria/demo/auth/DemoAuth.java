package dev.fondaria.demo.auth;

import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.json.JsonParser;
import dev.fondaria.demo.k8s.Kubectl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Gets a real, Keycloak-issued, RSA-signed access token for {@code contribution-api}'s calls (Task
 * D.3, `ADR-0026`). `contribution-api`'s `AuthorisationEnforcer` (Task 9.3) default-denies every
 * route unless the caller carries `INTERNAL_SERVICE` population, and Task D.0/D.2 predate that
 * enforcement -- this class is what makes `demo-tools` a valid caller again, the same way any other
 * internal service would authenticate: an OAuth2 client-credentials grant against the
 * `internal-services` confidential client (`infrastructure/keycloak/realm.json`).
 *
 * <p>Keycloak carries no NodePort (`ADR-0026`: nothing external should call it directly until Phase
 * 10's edge gateway exists), so every token fetch opens a transient {@code kubectl port-forward} --
 * the same pattern {@code advance} already uses for services with no public endpoint. The token
 * itself is cached and reused across calls within this process until shortly before it expires, so
 * a `demo-seed` run touching thousands of URLs does not open thousands of tunnels.
 */
public final class DemoAuth {

  private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  private final Kubectl kubectl;
  private final String realm;
  private final String clientId;
  private final String clientSecret;
  private final HttpClient http;

  private record CachedToken(String value, Instant expiresAt) {}

  private CachedToken cached;

  public DemoAuth() {
    this(
        new Kubectl(env("KUBE_CONTEXT", "kind-fondaria"), env("K8S_NAMESPACE", "fondaria")),
        env("KEYCLOAK_REALM", "fondaria"),
        env("KEYCLOAK_CLIENT_ID", "internal-services"),
        env("KEYCLOAK_CLIENT_SECRET", "internal-services-secret"));
  }

  DemoAuth(Kubectl kubectl, String realm, String clientId, String clientSecret) {
    this.kubectl = kubectl;
    this.realm = realm;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /**
   * Returns a valid bearer token, fetching a new one if none is cached or the cached one is stale.
   */
  public synchronized String bearerToken() {
    if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
      return cached.value();
    }
    cached = fetchToken();
    return cached.value();
  }

  private CachedToken fetchToken() {
    int localPort = 18080 + Math.abs("keycloak".hashCode() % 1000);
    try (Kubectl.PortForward forward = kubectl.portForward("keycloak", localPort, 8080)) {
      URI tokenUri =
          URI.create(
              "http://localhost:"
                  + localPort
                  + "/realms/"
                  + realm
                  + "/protocol/openid-connect/token");
      String form =
          "grant_type=client_credentials"
              + "&client_id="
              + urlEncode(clientId)
              + "&client_secret="
              + urlEncode(clientSecret);
      HttpRequest request =
          HttpRequest.newBuilder(tokenUri)
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (HttpTimeoutException e) {
        throw new DemoToolsException("Keycloak token request timed out", e);
      } catch (IOException e) {
        throw new DemoToolsException("could not reach Keycloak for a token", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DemoToolsException("interrupted requesting a Keycloak token", e);
      }
      if (response.statusCode() >= 400) {
        throw new DemoToolsException(
            "Keycloak token request failed: HTTP " + response.statusCode());
      }
      Map<String, Object> body = JsonParser.parseObject(response.body());
      String accessToken = (String) body.get("access_token");
      if (accessToken == null || accessToken.isBlank()) {
        throw new DemoToolsException("Keycloak token response carried no access_token");
      }
      long expiresInSeconds = JsonParser.longAt(body, "expires_in");
      Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds).minus(EXPIRY_SAFETY_MARGIN);
      return new CachedToken(accessToken, expiresAt);
    }
  }

  private static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}
