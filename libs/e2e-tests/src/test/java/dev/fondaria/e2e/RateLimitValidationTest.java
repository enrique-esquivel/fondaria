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
 * Task 10.3 (phase-10-edge.md) acceptance tests that do not require a live cluster: the
 * contribution-submission carve-out routes to contribution-api and matches POST only, both rate
 * limit policies are Global (shared across replicas, not a per-Envoy-instance Local bucket) and the
 * contributions policy is strictly tighter than the default on both the per-subject and
 * per-organization dimensions, the JWT provider extracts the headers the rate limit selectors
 * depend on, every application route strips them before the backend, the Redis backend and its
 * documented fail-open choice are wired into bootstrap, and the base/local replica-count split is
 * in place. {@code KubeconformIntegrationTest} covers schema validation.
 *
 * <p>Deferred to a live cluster (TASK-LOG.md): whether Envoy Gateway actually prefers this route's
 * RegularExpression+method match over httproute-employer.yaml's PathPrefix for the same
 * organizationId segment, and whether the Redis-backed counters genuinely hold across the three
 * replicas set here.
 */
class RateLimitValidationTest {

  private static final Path GATEWAY_PATH =
      Path.of(RepositoryRoot.path("infrastructure/k8s/base/gateway"));

  private static final List<String> ROUTES_STRIPPING_RATE_LIMIT_HEADERS =
      List.of(
          "httproute-participant.yaml",
          "httproute-employer.yaml",
          "httproute-org-summary.yaml",
          "httproute-employer-contributions.yaml");

  @Test
  @DisplayName("Rate limit manifests exist, parse, and are included by the base kustomization")
  void rateLimitManifestsExistAndParse() throws Exception {
    Yaml yaml = new Yaml();
    List<String> files =
        List.of(
            "httproute-employer-contributions.yaml",
            "backendtrafficpolicy-ratelimit-default.yaml",
            "backendtrafficpolicy-ratelimit-contributions.yaml");
    for (String file : files) {
      try (InputStream in = new FileInputStream(GATEWAY_PATH.resolve(file).toFile())) {
        assertThat(yaml.loadAll(in)).as("parsed documents in %s", file).isNotEmpty();
      }
    }

    String kustomization = Files.readString(GATEWAY_PATH.resolve("kustomization.yaml"));
    for (String file : files) {
      assertThat(kustomization).as("kustomization.yaml includes %s", file).contains(file);
    }
  }

  @Test
  @DisplayName("Contribution submission routes to contribution-api and matches POST only")
  void contributionRouteMatchesPostOnly() throws Exception {
    Map<String, Object> route = loadSingleDocument("httproute-employer-contributions.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) route.get("spec");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
    assertThat(rules).hasSize(1);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches = (List<Map<String, Object>>) rules.get(0).get("matches");
    assertThat(matches).hasSizeGreaterThanOrEqualTo(2);
    for (Map<String, Object> match : matches) {
      assertThat(match.get("method")).as("every match is POST-only").isEqualTo("POST");
      @SuppressWarnings("unchecked")
      Map<String, Object> path = (Map<String, Object>) match.get("path");
      assertThat(path.get("type")).isEqualTo("RegularExpression");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> backendRefs =
        (List<Map<String, Object>>) rules.get(0).get("backendRefs");
    assertThat(backendRefs.get(0).get("name")).isEqualTo("contribution-api");
  }

  @Test
  @DisplayName("Both rate limit policies are Global, and contributions is tighter than default")
  void contributionsPolicyIsTighterThanDefault() throws Exception {
    Map<Long, Long> defaultLimits =
        globalLimitsByHeader("backendtrafficpolicy-ratelimit-default.yaml");
    Map<Long, Long> contributionsLimits =
        globalLimitsByHeader("backendtrafficpolicy-ratelimit-contributions.yaml");

    assertThat(defaultLimits).hasSize(2);
    assertThat(contributionsLimits).hasSize(2);
    for (Long headerHash : defaultLimits.keySet()) {
      assertThat(contributionsLimits.get(headerHash))
          .as("contributions limit for the same selector must be tighter than the default")
          .isLessThan(defaultLimits.get(headerHash));
    }
  }

  @Test
  @DisplayName(
      "Both rate limit policies carry their own copy of the gateway's backend timeout (Row 29.8)")
  void rateLimitPoliciesCarryTheirOwnTimeout() throws Exception {
    // A BackendTrafficPolicy attached to an HTTPRoute is more specific than one attached to the
    // Gateway (fondaria-backend-defaults, backendtrafficpolicy.yaml) for the same route. Envoy
    // Gateway resolves same-kind policy attachment by specificity and replaces the whole policy
    // rather than merging it field by field -- confirmed live: `kubectl get backendtrafficpolicy
    // fondaria-backend-defaults -o yaml` reported Overridden=True for every route that has its own
    // rate-limit policy. Left unset here, those routes would silently lose the 5s connect / 30s
    // request timeout docs/ENGINEERING-RULES.md requires on every external call.
    Map<String, Object> gatewayDefault = loadSingleDocument("backendtrafficpolicy.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> expectedTimeout =
        (Map<String, Object>) ((Map<String, Object>) gatewayDefault.get("spec")).get("timeout");
    assertThat(expectedTimeout).as("gateway default policy itself has a timeout block").isNotNull();

    for (String file :
        List.of(
            "backendtrafficpolicy-ratelimit-default.yaml",
            "backendtrafficpolicy-ratelimit-contributions.yaml")) {
      Map<String, Object> policy = loadSingleDocument(file);
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) policy.get("spec");
      assertThat(spec.get("timeout"))
          .as(
              "%s must repeat the gateway's own timeout, or it silently overrides it away for"
                  + " every route it targets",
              file)
          .isEqualTo(expectedTimeout);
    }
  }

  @Test
  @DisplayName("The JWT provider extracts subject and organization claims for rate-limit headers")
  void jwtProviderExtractsRateLimitHeaders() throws Exception {
    Map<String, Object> policy = loadSingleDocument("securitypolicy-jwt.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> targetRefs = (List<Map<String, Object>>) spec.get("targetRefs");
    assertThat(targetRefs)
        .as("contribution submission must get the same edge JWT validation as every other route")
        .anyMatch(r -> "employer-api-contributions".equals(r.get("name")));

    @SuppressWarnings("unchecked")
    Map<String, Object> jwt = (Map<String, Object>) spec.get("jwt");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) jwt.get("providers");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> claimToHeaders =
        (List<Map<String, Object>>) providers.get(0).get("claimToHeaders");
    assertThat(claimToHeaders)
        .extracting(c -> c.get("header") + "=" + c.get("claim"))
        .containsExactlyInAnyOrder("X-Edge-RateLimit-Subject=sub", "X-Edge-RateLimit-Org=org_id");
  }

  @Test
  @DisplayName("Every application route strips the rate-limit headers before the backend")
  void everyRouteStripsRateLimitHeaders() throws Exception {
    for (String file : ROUTES_STRIPPING_RATE_LIMIT_HEADERS) {
      String content = Files.readString(GATEWAY_PATH.resolve(file));
      assertThat(content)
          .as("%s strips X-Edge-RateLimit-Subject", file)
          .contains("X-Edge-RateLimit-Subject");
      assertThat(content)
          .as("%s strips X-Edge-RateLimit-Org", file)
          .contains("X-Edge-RateLimit-Org");
    }
  }

  @Test
  @DisplayName("Three gateway replicas in base, patched down to one for local development")
  void replicaCountSplitBetweenBaseAndLocal() throws Exception {
    Map<String, Object> envoyProxy = loadSingleDocument("envoyproxy.yaml");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) envoyProxy.get("spec");
    @SuppressWarnings("unchecked")
    Map<String, Object> provider = (Map<String, Object>) spec.get("provider");
    @SuppressWarnings("unchecked")
    Map<String, Object> kubernetes = (Map<String, Object>) provider.get("kubernetes");
    @SuppressWarnings("unchecked")
    Map<String, Object> envoyDeployment = (Map<String, Object>) kubernetes.get("envoyDeployment");
    assertThat(envoyDeployment.get("replicas")).isEqualTo(3);

    String localKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml")));
    assertThat(localKustomization).contains("name: fondaria-gateway-proxy-config");
    assertThat(localKustomization).contains("/spec/provider/kubernetes/envoyDeployment/replicas");
  }

  @Test
  @DisplayName("Every HTTPRoute in base has a matching local-overlay hostname patch")
  void everyHttpRouteHasALocalHostnamePatch() throws Exception {
    // Regression test for a defect this task's own live verification found (TASK-LOG.md): adding
    // httproute-employer-contributions.yaml without a matching hostname patch reproduced exactly
    // the NoMatchingListenerHostname failure Task 10.2 already found and fixed for the original
    // four routes. Generic over every httproute-*.yaml so the next new route can't repeat it.
    String localKustomization =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml")));

    try (var stream = Files.walk(GATEWAY_PATH)) {
      List<Path> routeFiles =
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().startsWith("httproute-"))
              .toList();
      assertThat(routeFiles).isNotEmpty();
      for (Path routeFile : routeFiles) {
        Map<String, Object> route = loadSingleDocument(routeFile.getFileName().toString());
        String routeName = (String) ((Map<?, ?>) route.get("metadata")).get("name");
        assertThat(localKustomization)
            .as(
                "overlays/local/kustomization.yaml must patch hostnames/0 for HTTPRoute %s,"
                    + " or it fails to bind locally",
                routeName)
            .contains(
                "name: "
                    + routeName
                    + "\n    patch: |-\n      - op: replace\n"
                    + "        path: /spec/hostnames/0");
      }
    }
  }

  @Test
  @DisplayName(
      "Bootstrap wires a Redis-backed global rate limit with a documented fail-open choice")
  void bootstrapConfiguresRedisBackedRateLimit() throws Exception {
    String configMap =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/envoy-gateway-config.yaml")));
    assertThat(configMap).contains("name: envoy-gateway-config");
    assertThat(configMap).contains("namespace: envoy-gateway-system");
    assertThat(configMap).contains("type: Redis");
    assertThat(configMap).contains("valkey.fondaria.svc.cluster.local:6379");
    assertThat(configMap)
        .as("Redis-unavailable behaviour must be a documented, explicit choice, not the default")
        .contains("failClosed: false");

    String upScript =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/up.sh")));
    assertThat(upScript).contains("envoy-gateway-config.yaml");
    assertThat(upScript).contains("rollout restart deployment/envoy-gateway");
  }

  private Map<Long, Long> globalLimitsByHeader(String file) throws Exception {
    Map<String, Object> policy = loadSingleDocument(file);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");
    @SuppressWarnings("unchecked")
    Map<String, Object> rateLimit = (Map<String, Object>) spec.get("rateLimit");
    assertThat(rateLimit.get("type")).as("%s must be Global, not Local", file).isEqualTo("Global");
    @SuppressWarnings("unchecked")
    Map<String, Object> global = (Map<String, Object>) rateLimit.get("global");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) global.get("rules");

    Map<Long, Long> limitsByHeaderNameHash = new java.util.HashMap<>();
    for (Map<String, Object> rule : rules) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> selectors = (List<Map<String, Object>>) rule.get("clientSelectors");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> headers =
          (List<Map<String, Object>>) selectors.get(0).get("headers");
      String headerName = (String) headers.get(0).get("name");
      assertThat(headers.get(0).get("type"))
          .as("%s uses Distinct matching", file)
          .isEqualTo("Distinct");
      @SuppressWarnings("unchecked")
      Map<String, Object> limit = (Map<String, Object>) rule.get("limit");
      Number requests = (Number) limit.get("requests");
      limitsByHeaderNameHash.put((long) headerName.hashCode(), requests.longValue());
    }
    return limitsByHeaderNameHash;
  }

  private Map<String, Object> loadSingleDocument(String file) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(GATEWAY_PATH.resolve(file).toFile())) {
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
