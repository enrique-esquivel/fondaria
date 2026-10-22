package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
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
 * Task 10.1 (phase-10-edge.md) acceptance tests that do not require a live cluster: every manifest
 * under {@code infrastructure/k8s/base/gateway/} parses, the internal API (valuation-api,
 * /v1/funds, /v1/staff/actions) has structurally no route, and the local/prod overlays only differ
 * in hostnames and the TLS Secret's contents (its name, and the certificate Secret name
 * gateway.yaml references, are identical). {@code KubeconformIntegrationTest} and {@code
 * KustomizeOverlaysValidationTest} cover schema validation and the render-and-diff half; this class
 * covers what those do not: the specific routing/exposure claims Task 10.1 makes.
 *
 * <p>Deferred to a live cluster (TASK-LOG.md, not testable without one): whether Envoy Gateway
 * actually prefers httproute-org-summary.yaml's RegularExpression match over
 * httproute-employer.yaml's PathPrefix match, and whether EnvoyProxy's {@code envoyService.patch}
 * correctly pins the generated Service's NodePort.
 */
class GatewayBaseValidationTest {

  private static final List<String> GATEWAY_FILES =
      List.of(
          "kustomization.yaml",
          "gatewayclass.yaml",
          "envoyproxy.yaml",
          "gateway.yaml",
          "clienttrafficpolicy.yaml",
          "backendtrafficpolicy.yaml",
          "httproute-participant.yaml",
          "httproute-org-summary.yaml",
          "httproute-employer.yaml",
          "httproute-keycloak.yaml");

  private static final List<String> FORBIDDEN_INTERNAL_PREFIXES =
      List.of("/v1/funds", "/v1/staff", "/v1/valuation");

  @Test
  @DisplayName("All gateway manifests exist and parse as YAML")
  void allGatewayManifestsExistAndParse() throws Exception {
    Path gatewayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/base/gateway"));
    Yaml yaml = new Yaml();

    for (String file : GATEWAY_FILES) {
      File f = gatewayPath.resolve(file).toFile();
      assertThat(f).as("gateway manifest %s", file).isFile();
      try (InputStream in = new FileInputStream(f)) {
        Iterable<Object> documents = yaml.loadAll(in);
        assertThat(documents).as("parsed documents in %s", file).isNotEmpty();
      }
    }

    // The base kustomization must actually include this directory, or none of it is applied.
    String rootKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/base/kustomization.yaml")));
    assertThat(rootKustomization).contains("- gateway");
  }

  @Test
  @DisplayName(
      "GatewayClass controllerName matches the Envoy Gateway controller Task 10.1 installs")
  void gatewayClassControllerNameMatchesInstalledController() throws Exception {
    Map<String, Object> gatewayClass = loadSingleDocument("gatewayclass.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) gatewayClass.get("spec");
    assertThat(spec.get("controllerName"))
        .isEqualTo("gateway.envoyproxy.io/gatewayclass-controller");

    String versionsEnv =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/versions.env")));
    assertThat(versionsEnv).contains("ENVOY_GATEWAY_VERSION=");
    assertThat(versionsEnv).contains("ENVOY_GATEWAY_MANIFEST=");

    String upScript =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/up.sh")));
    assertThat(upScript).contains("gatewayclasses.gateway.networking.k8s.io");
    assertThat(upScript).contains("envoy-gateway-system");
  }

  @Test
  @DisplayName("Both listeners terminate TLS 1.3-only, per AGENTS.md and the task's own AC")
  void listenersTerminateTlsAtEdge() throws Exception {
    Map<String, Object> gateway = loadSingleDocument("gateway.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) gateway.get("spec");
    assertThat(spec.get("gatewayClassName")).isEqualTo("envoy-gateway");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> listeners = (List<Map<String, Object>>) spec.get("listeners");
    assertThat(listeners).hasSize(2);

    for (Map<String, Object> listener : listeners) {
      assertThat(listener.get("protocol")).isEqualTo("HTTPS");
      assertThat(listener.get("port")).isEqualTo(443);
      @SuppressWarnings("unchecked")
      Map<String, Object> tls = (Map<String, Object>) listener.get("tls");
      assertThat(tls.get("mode")).as("listener %s", listener.get("name")).isEqualTo("Terminate");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> certRefs = (List<Map<String, Object>>) tls.get("certificateRefs");
      assertThat(certRefs).extracting(r -> r.get("name")).containsOnly("fondaria-gateway-tls");
    }

    Map<String, Object> clientPolicy = loadSingleDocument("clienttrafficpolicy.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> policySpec = (Map<String, Object>) clientPolicy.get("spec");
    @SuppressWarnings("unchecked")
    Map<String, Object> tlsSettings = (Map<String, Object>) policySpec.get("tls");
    assertThat(tlsSettings.get("minVersion")).isEqualTo("1.3");
  }

  @Test
  @DisplayName(
      "The internal API has no route: valuation-api, /v1/funds and /v1/staff are unreachable")
  void internalApiHasNoRoute() throws Exception {
    Path gatewayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/base/gateway"));
    List<String> httpRouteFiles =
        List.of(
            "httproute-participant.yaml",
            "httproute-org-summary.yaml",
            "httproute-employer.yaml",
            "httproute-keycloak.yaml");

    for (String file : httpRouteFiles) {
      String content = Files.readString(gatewayPath.resolve(file));
      assertThat(content)
          .as("%s must never reference valuation-api as a backend", file)
          .doesNotContain("name: valuation-api");
      for (String forbidden : FORBIDDEN_INTERNAL_PREFIXES) {
        assertThat(content)
            .as("%s must never route the internal-only prefix %s", file, forbidden)
            .doesNotContain("value: " + forbidden);
      }
    }

    // And no other gateway manifest names valuation-api as a backendRef either (defence against a
    // route being added under a name that doesn't match "httproute-*"). Matches the backendRef
    // form specifically -- gateway.yaml's own prose comment names valuation-api descriptively,
    // which is not a route and must not fail this check.
    try (var stream = Files.walk(gatewayPath)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        if (file.getFileName().toString().startsWith("httproute-")) {
          continue; // already checked above with the full list
        }
        String content = Files.readString(file);
        assertThat(content)
            .as("%s must never reference valuation-api as a backend", file)
            .doesNotContain("name: valuation-api");
      }
    }
  }

  @Test
  @DisplayName("Participant and employer routes target the right backend Service on api-https")
  void routesTargetExpectedBackends() throws Exception {
    assertRouteTargets(
        "httproute-participant.yaml", "api-https", "/v1/participants", "balance-api");
    assertRouteTargets(
        "httproute-employer.yaml", "api-https", "/v1/organizations", "contribution-api");
    assertRouteTargets("httproute-keycloak.yaml", "auth-https", "/", "keycloak");

    Map<String, Object> summaryRoute = loadSingleDocument("httproute-org-summary.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) summaryRoute.get("spec");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches = (List<Map<String, Object>>) rules.get(0).get("matches");
    @SuppressWarnings("unchecked")
    Map<String, Object> path = (Map<String, Object>) matches.get(0).get("path");
    assertThat(path.get("type")).isEqualTo("RegularExpression");
    assertThat((String) path.get("value")).contains("/summary");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> backendRefs =
        (List<Map<String, Object>>) rules.get(0).get("backendRefs");
    assertThat(backendRefs.get(0).get("name")).isEqualTo("balance-api");
  }

  @Test
  @DisplayName("Local overlay removes the three raw API NodePorts and exposes only the gateway's")
  void localOverlayRemovesDirectApiNodePorts() throws Exception {
    String localKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml")));

    assertThat(localKustomization)
        .as("valuation-api (the internal API) must never get a NodePort again")
        .doesNotContain("name: valuation-api\n    patch:");
    assertThat(localKustomization).contains("nodePort: 30443");
    assertThat(localKustomization).contains("kind: Gateway");
    assertThat(localKustomization).contains("api.fondaria.local");
    assertThat(localKustomization).contains("auth.fondaria.local");

    String kindConfig =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/kind-config.yaml")));
    assertThat(kindConfig).contains("containerPort: 30443").contains("hostPort: 30443");
    assertThat(kindConfig)
        .doesNotContain("hostPort: 30080")
        .doesNotContain("hostPort: 30081")
        .doesNotContain("hostPort: 30082");
  }

  @Test
  @DisplayName("Local and prod both name the gateway TLS Secret fondaria-gateway-tls")
  void localAndProdNameTheSameTlsSecret() throws Exception {
    String localKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml")));
    assertThat(localKustomization).contains("name: fondaria-gateway-tls");

    String prodCertificates =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/prod/certificates.yaml")));
    assertThat(prodCertificates).contains("secretName: fondaria-gateway-tls");

    // Public-release hardening (TASK-LOG step 118): the local pair is no longer committed. The
    // local overlay reads it from generated/, which local-secrets.sh fills at bootstrap and
    // .gitignore keeps out of the tree (PublicReleaseSecretsGuardTest checks no key is tracked).
    assertThat(localKustomization)
        .contains("tls.crt=generated/gateway-tls.crt")
        .contains("tls.key=generated/gateway-tls.key");
    String localSecretsScript =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/local-secrets.sh")));
    assertThat(localSecretsScript)
        .contains("gateway-tls.crt")
        .contains("gateway-tls.key")
        .contains("subjectAltName=DNS:api.fondaria.local,DNS:auth.fondaria.local");
    assertThat(Files.readString(Path.of(RepositoryRoot.path(".gitignore"))))
        .contains("infrastructure/k8s/overlays/local/generated/");
  }

  private void assertRouteTargets(
      String file, String sectionName, String pathPrefix, String backendName) throws Exception {
    Map<String, Object> route = loadSingleDocument(file);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) route.get("spec");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parentRefs = (List<Map<String, Object>>) spec.get("parentRefs");
    assertThat(parentRefs.get(0).get("name")).isEqualTo("fondaria-gateway");
    assertThat(parentRefs.get(0).get("sectionName")).isEqualTo(sectionName);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches = (List<Map<String, Object>>) rules.get(0).get("matches");
    @SuppressWarnings("unchecked")
    Map<String, Object> path = (Map<String, Object>) matches.get(0).get("path");
    assertThat(path.get("value")).isEqualTo(pathPrefix);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> backendRefs =
        (List<Map<String, Object>>) rules.get(0).get("backendRefs");
    assertThat(backendRefs.get(0).get("name")).isEqualTo(backendName);
  }

  private Map<String, Object> loadSingleDocument(String file) throws Exception {
    Path gatewayPath = Path.of(RepositoryRoot.path("infrastructure/k8s/base/gateway"));
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(gatewayPath.resolve(file).toFile())) {
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
