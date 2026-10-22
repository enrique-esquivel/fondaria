package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 10.2 (phase-10-edge.md) acceptance tests that do not require a live cluster: {@code
 * securitypolicy-jwt.yaml}'s shape (issuer/audience/remoteJWKS, and which routes it does and does
 * not target), and the {@code X-Caller-Identity} header strip on the three routes it protects.
 *
 * <p>Live-cluster behaviour (forged/missing signature rejected with the application never seeing
 * the request, TLS 1.1 refused, the Keycloak route left ungated) was verified this session against
 * a real kind cluster running Envoy Gateway 1.3.0 -- see TASK-LOG.md. Not re-asserted here since
 * this class, like {@code GatewayBaseValidationTest}, runs with no container dependencies.
 */
class EdgeJwtSecurityPolicyValidationTest {

  private static final Path GATEWAY_DIR =
      Path.of(RepositoryRoot.path("infrastructure/k8s/base/gateway"));

  @Test
  @DisplayName("securitypolicy-jwt.yaml parses and is wired into the gateway kustomization")
  void securityPolicyManifestExistsAndIsWired() throws Exception {
    assertThat(GATEWAY_DIR.resolve("securitypolicy-jwt.yaml").toFile()).isFile();

    String kustomization = Files.readString(GATEWAY_DIR.resolve("kustomization.yaml"));
    assertThat(kustomization).contains("securitypolicy-jwt.yaml");
  }

  @Test
  @DisplayName(
      "The JWT provider checks this realm's issuer and a fixed audience, never both routes and Keycloak")
  void jwtProviderTargetsOnlyTheApplicationRoutes() throws Exception {
    Map<String, Object> policy = loadSingleDocument("securitypolicy-jwt.yaml");
    assertThat(policy.get("kind")).isEqualTo("SecurityPolicy");

    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> targetRefs = (List<Map<String, Object>>) spec.get("targetRefs");
    List<String> targetNames = targetRefs.stream().map(r -> (String) r.get("name")).toList();
    // Task 10.3 added employer-api-contributions (the tighter-rate-limited contribution
    // submission carve-out); it needs the same edge JWT validation as every other application
    // route, not a weaker one just because it's new.
    assertThat(targetNames)
        .containsExactlyInAnyOrder(
            "participant-api",
            "employer-api",
            "employer-api-org-summary",
            "employer-api-contributions");
    assertThat(targetNames)
        .as("must never gate Keycloak's own login/token/JWKS endpoints behind its own JWKS check")
        .doesNotContain("keycloak");
    assertThat(targetRefs)
        .as("SecurityPolicy targetRefs do not support sectionName (Gateway API kind restriction)")
        .allSatisfy(r -> assertThat(r.get("kind")).isEqualTo("HTTPRoute"));

    @SuppressWarnings("unchecked")
    Map<String, Object> jwt = (Map<String, Object>) spec.get("jwt");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) jwt.get("providers");
    assertThat(providers).hasSize(1);
    Map<String, Object> provider = providers.get(0);

    assertThat((String) provider.get("issuer")).contains("/realms/fondaria");
    @SuppressWarnings("unchecked")
    List<String> audiences = (List<String>) provider.get("audiences");
    assertThat(audiences).containsExactly("fondaria-api");

    @SuppressWarnings("unchecked")
    Map<String, Object> remoteJwks = (Map<String, Object>) provider.get("remoteJWKS");
    assertThat((String) remoteJwks.get("uri"))
        .as("must resolve in-cluster, not through the gateway's own public listener")
        .startsWith("http://keycloak.")
        .contains("/realms/fondaria/protocol/openid-connect/certs");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> backendRefs =
        (List<Map<String, Object>>) remoteJwks.get("backendRefs");
    assertThat(backendRefs.get(0).get("name")).isEqualTo("keycloak");
  }

  @Test
  @DisplayName(
      "The local overlay patches the JWT provider's issuer to auth.fondaria.local, matching the "
          + "Gateway listener and KC_HOSTNAME patches")
  void localOverlayPatchesIssuerToMatchLocalHostname() throws Exception {
    String localKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml")));
    assertThat(localKustomization).contains("kind: SecurityPolicy");
    assertThat(localKustomization).contains("name: fondaria-edge-jwt");
    assertThat(localKustomization).contains("https://auth.fondaria.local/realms/fondaria");

    // Task 10.2's own discovered defect: the Gateway listener hostname patch existed without a
    // matching patch on each HTTPRoute's own `spec.hostnames`, so no route ever bound locally
    // (Gateway API requires the two to intersect). Asserted here so a future edit cannot silently
    // drop the fix.
    assertThat(localKustomization).contains("api.fondaria.local").contains("auth.fondaria.local");
    for (String routeName :
        List.of("participant-api", "employer-api", "employer-api-org-summary", "keycloak")) {
      assertThat(localKustomization)
          .as("local overlay must patch %s's own hostnames, not just the Gateway's", routeName)
          .containsPattern("kind: HTTPRoute\\s*\\n\\s*name: " + routeName);
    }
  }

  @Test
  @DisplayName(
      "The three protected routes strip X-Caller-Identity, so a client cannot inject the header "
          + "Task 9.2 removed application support for")
  void protectedRoutesStripLegacyIdentityHeader() throws Exception {
    for (String file :
        List.of(
            "httproute-participant.yaml",
            "httproute-employer.yaml",
            "httproute-org-summary.yaml")) {
      Map<String, Object> route = loadSingleDocument(file);
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) route.get("spec");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> filters = (List<Map<String, Object>>) rules.get(0).get("filters");
      assertThat(filters).as("%s must declare a filters list", file).isNotEmpty();

      Map<String, Object> headerFilter = filters.get(0);
      assertThat(headerFilter.get("type")).isEqualTo("RequestHeaderModifier");
      @SuppressWarnings("unchecked")
      Map<String, Object> modifier =
          (Map<String, Object>) headerFilter.get("requestHeaderModifier");
      @SuppressWarnings("unchecked")
      List<String> removed = (List<String>) modifier.get("remove");
      assertThat(removed).contains("X-Caller-Identity");
    }

    // Keycloak's own route parses no identity out of the request at all -- it must not carry
    // this filter (nothing to strip an identity header in front of).
    Map<String, Object> keycloakRoute = loadSingleDocument("httproute-keycloak.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) keycloakRoute.get("spec");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
    assertThat(rules.get(0).get("filters")).isNull();
  }

  private Map<String, Object> loadSingleDocument(String file) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(GATEWAY_DIR.resolve(file).toFile())) {
      List<Object> docs = new ArrayList<>();
      for (Object d : yaml.loadAll(in)) {
        docs.add(d);
      }
      assertThat(docs).as("%s must contain exactly one document", file).hasSize(1);
      @SuppressWarnings("unchecked")
      Map<String, Object> doc = (Map<String, Object>) docs.get(0);
      return doc;
    }
  }
}
