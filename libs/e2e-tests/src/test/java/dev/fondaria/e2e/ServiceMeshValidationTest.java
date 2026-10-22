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
 * Task 10.4 (phase-10-edge.md) / ADR-0029's storage amendment, completed by Task 29.11: acceptance
 * tests that do not require a live cluster. Every application-tier Deployment this platform owns,
 * plus RabbitMQ, Valkey, the object store and Postgres, is annotated for mesh injection; only
 * tigerbeetle-cdc (no inbound HTTP surface) and TigerBeetle itself (Direct IO/io_uring/replication
 * risk) are never annotated. The Envoy Gateway dataplane is injected via EnvoyProxy rather than the
 * controller pod. The two edge-to-app and four storage call paths each get their own Server +
 * AuthorizationPolicy, app-tier callers sharing one MeshTLSAuthentication per destination. Three of
 * the four storage destinations (RabbitMQ, Valkey, the object store) also grant two real unmeshed
 * callers (Task 10.3's envoy-ratelimit, tigerbeetle-cdc, object-store-buckets) a cluster-CIDR
 * NetworkAuthentication fallback in a SEPARATE AuthorizationPolicy each (multiple authentication
 * refs within one AuthorizationPolicy are AND'd, not OR'd -- Linkerd's own reference docs -- so the
 * fallback only works as a fallback split across two policies on the same Server); Postgres has
 * none left (every JDBC caller, including Keycloak and Postgres's own inter-instance replication,
 * is meshed), so its Server is targeted by exactly one AuthorizationPolicy. The cross-namespace
 * NetworkPolicy gap this task found live is fixed and wired in, and bootstrap installs Linkerd with
 * the committed local-dev trust anchor and a cluster-wide deny default.
 *
 * <p>Live-verified across Tasks 10.4/10.9/10.10/29.12 and this session (TASK-LOG.md): mesh
 * injection works despite every app-tier pod's automountServiceAccountToken: false; an
 * unmeshed/plaintext caller to a Server-gated port is cleanly denied (403, not a hang). The real
 * root cause of every prior session's "meshed-to-meshed call never succeeds" finding was identified
 * via tcpdump on a calling pod's own interface: a meshed caller always dials the destination's
 * Linkerd inbound proxy port (4143) directly, and no per-service NetworkPolicy ever named it --
 * fixed (networkpolicies/allow-mesh-proxy.yaml), confirmed with a full, zero-workaround, real
 * end-to-end authenticated round trip through the edge. Meshing Postgres was first attempted
 * without its own fix and reverted after it broke JDBC connectivity platform-wide (Connection reset
 * inside the driver's own SSL negotiation, wrapped a second time by Linkerd's opaque TCP proxying);
 * Task 29.11 is that fix -- every JDBC caller's own URL now disables Postgres's redundant SSL layer
 * (`sslmode=disable`) before Postgres is meshed, live-verified against a real cluster with zero
 * crash loops.
 */
class ServiceMeshValidationTest {

  private static final Path BASE_PATH = Path.of(RepositoryRoot.path("infrastructure/k8s/base"));
  private static final Path POLICY_PATH = BASE_PATH.resolve("linkerd-policy");
  private static final Path NETPOL_PATH = BASE_PATH.resolve("networkpolicies");

  private static final List<String> MESHED_APP_SERVICES =
      List.of(
          "contribution-api",
          "balance-api",
          "valuation-api",
          "allotment-job",
          "eod-job",
          "projection-worker",
          "statement-worker",
          "amqp-fanout",
          "keycloak");

  private static final List<String> NEVER_MESHED_SERVICES =
      List.of(
          "tigerbeetle-cdc", // no inbound HTTP surface -- ADR-0029
          "tigerbeetle" // Direct IO/io_uring/replication risk -- ADR-0029
          );

  private static final List<String> MESHED_STORAGE_SERVICES =
      List.of("rabbitmq", "valkey", "object-store", "postgres");

  // Task 29.11: Postgres has no unmeshed caller left, so its Server is targeted by one
  // AuthorizationPolicy (the MeshTLSAuthentication grant), not the two the other three storage
  // services carry (grant + CIDR fallback).
  private static final List<String> STORAGE_SERVICES_WITH_UNMESHED_FALLBACK =
      List.of("rabbitmq", "valkey", "object-store");

  private static final List<String> EDGE_AUTHORIZED_SERVICES =
      List.of("contribution-api", "balance-api", "keycloak");

  private static final Map<String, String> STORAGE_SERVER_PORT_BY_SERVICE =
      Map.of(
          "rabbitmq", "rabbitmq-tcp",
          "valkey", "valkey-tcp",
          "object-store", "object-store-http",
          "postgres", "postgres-tcp");

  @Test
  @DisplayName("Every application-tier Deployment this platform owns is annotated for injection")
  void everyAppTierDeploymentIsMeshed() throws Exception {
    // A whole-file substring check would still pass if the annotation moved to the Deployment's
    // own metadata.annotations (where the proxy-injector webhook never sees it, so it injects
    // nothing) -- phase-closing adversarial review. Parse and check the exact pod-template path.
    Yaml yaml = new Yaml();
    for (String service : MESHED_APP_SERVICES) {
      Path deployment = BASE_PATH.resolve(service).resolve("deployment.yaml");
      assertThat(Files.exists(deployment)).as("%s exists", deployment).isTrue();
      Map<String, Object> doc;
      try (InputStream in = new FileInputStream(deployment.toFile())) {
        doc = yaml.load(in);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> template = (Map<String, Object>) spec.get("template");
      @SuppressWarnings("unchecked")
      Map<String, Object> templateMetadata = (Map<String, Object>) template.get("metadata");
      @SuppressWarnings("unchecked")
      Map<String, Object> annotations = (Map<String, Object>) templateMetadata.get("annotations");
      assertThat(annotations)
          .as("%s's pod template must carry an annotations block", service)
          .isNotNull();
      assertThat(annotations.get("linkerd.io/inject"))
          .as(
              "%s's pod TEMPLATE annotation (spec.template.metadata.annotations) must enable"
                  + " injection -- a Deployment-level annotation is invisible to the injector",
              service)
          .isEqualTo("enabled");
    }
  }

  @Test
  @DisplayName("tigerbeetle-cdc and TigerBeetle itself are never annotated for mesh injection")
  void tigerbeetleAndCdcAreNeverMeshed() throws Exception {
    for (String name : NEVER_MESHED_SERVICES) {
      Path dir = BASE_PATH.resolve(name);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var stream = Files.walk(dir, 1)) {
        for (Path file : stream.filter(Files::isRegularFile).toList()) {
          String fileName = file.getFileName().toString();
          if (!fileName.endsWith(".yaml")) {
            continue;
          }
          String content = Files.readString(file);
          assertThat(content)
              .as(
                  "%s must not be mesh-injected (ADR-0029: NetworkPolicy-only for the datastore"
                      + " tier and for tigerbeetle-cdc's no-inbound-surface exclusion)",
                  file)
              .doesNotContain("linkerd.io/inject: enabled");
        }
      }
    }
  }

  @Test
  @DisplayName("RabbitMQ, Valkey, the object store and Postgres are annotated for mesh injection")
  void meshedStorageServicesAreAnnotated() throws Exception {
    // Each lives under a different operator CR shape (RabbitmqCluster's override.statefulSet,
    // CNPG-style plain StatefulSets for two of them, CNPG's own Cluster CR for Postgres), so a
    // single YAML-path parse doesn't fit all four the way it does the uniform app-tier Deployments
    // -- whole-file substring check, same as tigerbeetleAndCdcAreNeverMeshed's own negative check.
    Map<String, String> fileByService =
        Map.of(
            "rabbitmq", "rabbitmq-cluster.yaml",
            "valkey", "valkey-cluster.yaml",
            "object-store", "statefulset.yaml",
            "postgres", "cluster.yaml");
    for (String service : MESHED_STORAGE_SERVICES) {
      Path file = BASE_PATH.resolve(service).resolve(fileByService.get(service));
      assertThat(Files.exists(file)).as("%s exists", file).isTrue();
      String content = Files.readString(file);
      assertThat(content)
          .as("%s's pod template must carry linkerd.io/inject: enabled", service)
          .contains("linkerd.io/inject: enabled");
    }
  }

  @Test
  @DisplayName("Every meshed storage service has its own Server and per-caller-class authorization")
  void everyMeshedStorageServiceHasItsOwnServerAndAuthorization() throws Exception {
    List<Map<String, Object>> docs = loadAllDocuments("storage-authorization.yaml");

    assertThat(findByKindAndName(docs, "NetworkAuthentication", "unmeshed-cluster-callers"))
        .as("the shared unmeshed-caller CIDR fallback must exist")
        .isPresent();

    for (String service : MESHED_STORAGE_SERVICES) {
      String serverName = STORAGE_SERVER_PORT_BY_SERVICE.get(service);
      assertThat(findByKindAndName(docs, "Server", serverName))
          .as("%s must have its own Server", service)
          .isPresent();

      List<Map<String, Object>> authzPolicies =
          docs.stream()
              .filter(d -> "AuthorizationPolicy".equals(d.get("kind")))
              .filter(
                  d -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spec = (Map<String, Object>) d.get("spec");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> targetRef = (Map<String, Object>) spec.get("targetRef");
                    return serverName.equals(targetRef.get("name"));
                  })
              .toList();
      int expectedCount = STORAGE_SERVICES_WITH_UNMESHED_FALLBACK.contains(service) ? 2 : 1;
      assertThat(authzPolicies)
          .as(
              STORAGE_SERVICES_WITH_UNMESHED_FALLBACK.contains(service)
                  ? "%s's Server must be targeted by exactly two AuthorizationPolicies: the"
                      + " app-tier MeshTLSAuthentication grant and the unmeshed-caller CIDR"
                      + " fallback, as two SEPARATE policies (multiple requiredAuthenticationRefs"
                      + " within one policy are AND'd, not OR'd, which would make a single merged"
                      + " policy meaningless as a fallback)"
                  : "%s's Server must be targeted by exactly one AuthorizationPolicy -- it has no"
                      + " real caller left without a mesh identity, so no CIDR fallback is needed",
              service)
          .hasSize(expectedCount);
    }
  }

  @Test
  @DisplayName(
      "Every Postgres JDBC caller's own datasource config disables Postgres's redundant SSL layer")
  void everyPostgresCallerDisablesRedundantSsl() throws Exception {
    // Task 29.11's actual fix: Postgres's own SSL-negotiation preamble ("send a plaintext byte,
    // maybe upgrade to TLS in-band") is genuinely incompatible with a second TLS layer wrapped
    // around it by Linkerd's opaque TCP proxying -- confirmed live (TASK-LOG.md step 86) as
    // `Connection reset` inside the JDBC driver's own SSL handshake, crash-looping the whole app
    // tier. Linkerd's own mTLS already encrypts the connection, so every real JDBC caller must ask
    // Postgres to skip its own layer instead -- checked here so the mesh Server/AuthorizationPolicy
    // trio in storage-authorization.yaml is never applied without this half of the fix landing in
    // the same commit.
    for (String service : MESHED_APP_SERVICES) {
      if ("keycloak".equals(service) || "amqp-fanout".equals(service)) {
        continue; // handled separately below (Keycloak) or has no Postgres connection (amqp-fanout)
      }
      Path configMap = BASE_PATH.resolve(service).resolve("configmap.yaml");
      if (!Files.exists(configMap)) {
        continue;
      }
      String content = Files.readString(configMap);
      if (!content.contains("POSTGRES_URL")) {
        continue;
      }
      assertThat(content)
          .as("%s's POSTGRES_URL must carry sslmode=disable", service)
          .containsPattern("POSTGRES_URL:\\s*\"jdbc:postgresql://[^\"]*\\?sslmode=disable\"");
    }

    String keycloakConfig =
        Files.readString(BASE_PATH.resolve("keycloak").resolve("app-config.yaml"));
    assertThat(keycloakConfig)
        .as(
            "keycloak's KC_DB_URL_PROPERTIES must carry sslmode=disable, the Quarkus-driver"
                + " equivalent of every other service's POSTGRES_URL query string")
        .contains("KC_DB_URL_PROPERTIES: \"?sslmode=disable\"");
  }

  @Test
  @DisplayName("The Envoy Gateway dataplane, not the controller, is annotated for mesh injection")
  void envoyGatewayDataplaneIsMeshed() throws Exception {
    Path envoyProxy = BASE_PATH.resolve("gateway").resolve("envoyproxy.yaml");
    String content = Files.readString(envoyProxy);
    assertThat(content).contains("linkerd.io/inject: enabled");
    assertThat(content)
        .as("annotation must be under the generated pod's own template")
        .contains("pod:\n          annotations:\n            linkerd.io/inject: enabled");
  }

  @Test
  @DisplayName("The dataplane's public listener and metrics port bypass mesh policy entirely")
  void dataplanePublicPortsSkipInboundMeshPolicy() throws Exception {
    // Phase-closing adversarial review (CONFIRMED, critical): meshing the dataplane under the
    // cluster's own --default-inbound-policy=deny with no exemption for its own inbound ports
    // takes the entire public API offline -- external clients have no mesh identity, and
    // Prometheus (deliberately unmeshed, ADR-0029) can no longer reach the stats port either.
    // Live-verified this session: 10443 is gateway.yaml's public HTTPS listener (both api-https
    // and auth-https, port 443 -> +10000 for the non-root container) and 19001 is the
    // stats/metrics port (Task 10.2's EdgeJwksFetchFailing alert already scrapes it).
    Path envoyProxy = BASE_PATH.resolve("gateway").resolve("envoyproxy.yaml");
    String content = Files.readString(envoyProxy);
    assertThat(content)
        .as("the public HTTPS listener and metrics port must skip mesh inbound interception")
        .contains("config.linkerd.io/skip-inbound-ports: \"10443,19001\"");
  }

  @Test
  @DisplayName("linkerd-policy manifests exist, parse, and are included by the base kustomization")
  void linkerdPolicyManifestsExistAndParse() throws Exception {
    Yaml yaml = new Yaml();
    Path file = POLICY_PATH.resolve("edge-to-app-authorization.yaml");
    try (InputStream in = new FileInputStream(file.toFile())) {
      assertThat(yaml.loadAll(in)).as("parsed documents in %s", file).hasSize(7);
    }

    String policyKustomization = Files.readString(POLICY_PATH.resolve("kustomization.yaml"));
    assertThat(policyKustomization).contains("edge-to-app-authorization.yaml");

    String baseKustomization = Files.readString(BASE_PATH.resolve("kustomization.yaml"));
    assertThat(baseKustomization).contains("linkerd-policy");
  }

  @Test
  @DisplayName("Every real edge-to-app call path gets its own Server and AuthorizationPolicy")
  void everyEdgeCallPathHasItsOwnServerAndAuthorizationPolicy() throws Exception {
    List<Map<String, Object>> docs = loadAllDocuments("edge-to-app-authorization.yaml");

    Map<String, Object> meshTlsAuth =
        docs.stream()
            .filter(d -> "MeshTLSAuthentication".equals(d.get("kind")))
            .findFirst()
            .orElseThrow();
    String authName = (String) ((Map<?, ?>) meshTlsAuth.get("metadata")).get("name");
    @SuppressWarnings("unchecked")
    List<String> identities =
        (List<String>) ((Map<String, Object>) meshTlsAuth.get("spec")).get("identities");
    assertThat(identities)
        .as("edge identity must scope to envoy-gateway-system, not match every mesh identity")
        .allMatch(
            id ->
                id.endsWith(".envoy-gateway-system.serviceaccount.identity.linkerd.cluster.local"));

    for (String service : EDGE_AUTHORIZED_SERVICES) {
      Map<String, Object> server =
          findByKindAndName(docs, "Server", service + "-http")
              .orElseThrow(() -> new AssertionError(service + " must have its own Server"));
      @SuppressWarnings("unchecked")
      Map<String, Object> serverSpec = (Map<String, Object>) server.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> podSelector = (Map<String, Object>) serverSpec.get("podSelector");
      @SuppressWarnings("unchecked")
      Map<String, Object> matchLabels = (Map<String, Object>) podSelector.get("matchLabels");
      assertThat(matchLabels.get("app.kubernetes.io/name")).isEqualTo(service);
      assertThat(serverSpec.get("port")).isEqualTo("http");

      Map<String, Object> authzPolicy =
          findByKindAndName(docs, "AuthorizationPolicy", service + "-allow-edge")
              .orElseThrow(
                  () -> new AssertionError(service + " must have its own AuthorizationPolicy"));
      @SuppressWarnings("unchecked")
      Map<String, Object> authzSpec = (Map<String, Object>) authzPolicy.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> targetRef = (Map<String, Object>) authzSpec.get("targetRef");
      assertThat(targetRef.get("kind")).isEqualTo("Server");
      assertThat(targetRef.get("name"))
          .as("%s's AuthorizationPolicy must target its own Server, not another service's", service)
          .isEqualTo(service + "-http");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> requiredAuthRefs =
          (List<Map<String, Object>>) authzSpec.get("requiredAuthenticationRefs");
      assertThat(requiredAuthRefs).hasSize(1);
      assertThat(requiredAuthRefs.get(0).get("kind")).isEqualTo("MeshTLSAuthentication");
      assertThat(requiredAuthRefs.get(0).get("name")).isEqualTo(authName);
    }
  }

  @Test
  @DisplayName("Deleting one service's AuthorizationPolicy cannot affect another service's Server")
  void authorizationPoliciesAreIndependentPerService() throws Exception {
    // Regression guard for the acceptance criterion itself: every AuthorizationPolicy's
    // targetRef.name must be unique, so removing one document can only ever drop exactly one
    // call path's authorization, never another's.
    List<Map<String, Object>> docs = loadAllDocuments("edge-to-app-authorization.yaml");
    List<String> targetNames = new ArrayList<>();
    for (Map<String, Object> doc : docs) {
      if (!"AuthorizationPolicy".equals(doc.get("kind"))) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
      @SuppressWarnings("unchecked")
      Map<String, Object> targetRef = (Map<String, Object>) spec.get("targetRef");
      targetNames.add((String) targetRef.get("name"));
    }
    assertThat(targetNames).hasSize(EDGE_AUTHORIZED_SERVICES.size());
    assertThat(targetNames).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("The cross-namespace NetworkPolicy gap for the mesh control plane is fixed")
  void networkPolicyAllowsEgressToLinkerdControlPlane() throws Exception {
    // Regression test for a defect this task's own live verification found: default-deny-all
    // blocks every meshed pod's own linkerd-proxy from reaching linkerd-identity/-dst/-policy in
    // the `linkerd` namespace, hanging every injected pod in Init forever.
    Path file = NETPOL_PATH.resolve("allow-linkerd-control-plane.yaml");
    Map<String, Object> policy = loadSingleDocument(file);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");
    assertThat(spec.get("podSelector"))
        .as("must apply to every pod, not one service")
        .isEqualTo(Map.of());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> egress = (List<Map<String, Object>>) spec.get("egress");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ports = (List<Map<String, Object>>) egress.get(0).get("ports");
    List<Integer> allowedPorts = ports.stream().map(p -> (Integer) p.get("port")).toList();
    assertThat(allowedPorts).containsExactlyInAnyOrder(8080, 8086, 8090);

    String netpolKustomization = Files.readString(NETPOL_PATH.resolve("kustomization.yaml"));
    assertThat(netpolKustomization).contains("allow-linkerd-control-plane.yaml");
  }

  @Test
  @DisplayName("Every meshed pod's own inbound proxy port is allowed, cluster-wide")
  void networkPolicyAllowsIngressToTheMeshProxyPort() throws Exception {
    // Regression test for the real root cause behind two prior sessions' "environment quirk"
    // misdiagnosis: a meshed caller's outbound proxy always dials the destination's Linkerd
    // inbound proxy port (4143) directly, never the application's own port -- confirmed live via
    // tcpdump. No per-service NetworkPolicy named 4143, so default-deny-all silently dropped
    // every proxy-to-proxy SYN. podSelector: {}, not a per-service rule, since any meshed pod can
    // be a mesh call's destination.
    Path file = NETPOL_PATH.resolve("allow-mesh-proxy.yaml");
    Map<String, Object> policy = loadSingleDocument(file);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");
    assertThat(spec.get("podSelector"))
        .as("must apply to every pod, not one service")
        .isEqualTo(Map.of());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ingress = (List<Map<String, Object>>) spec.get("ingress");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ports = (List<Map<String, Object>>) ingress.get(0).get("ports");
    List<Integer> allowedPorts = ports.stream().map(p -> (Integer) p.get("port")).toList();
    assertThat(allowedPorts).containsExactly(4143);

    String netpolKustomization = Files.readString(NETPOL_PATH.resolve("kustomization.yaml"));
    assertThat(netpolKustomization).contains("allow-mesh-proxy.yaml");
  }

  @Test
  @DisplayName(
      "Every meshed pod may also dial another meshed pod's inbound proxy port, cluster-wide")
  void networkPolicyAllowsEgressToTheMeshProxyPort() throws Exception {
    // Regression test for row 29.12: the ingress-only fix above (Task 10.4) was half the
    // problem. Every per-service NetworkPolicy's own `egress` list names only the pre-mesh
    // application port per destination (RabbitMQ 5672, Valkey 6379, object store 8333) -- never
    // 4143 --
    // so meshing the storage tier made every NEW caller-to-storage connection dial 4143 outbound,
    // which default-deny on egress silently dropped before it ever left the calling pod's own
    // veth. Confirmed live via tcpdump on both ends simultaneously and via the calling pod's own
    // Calico egress counters never incrementing across repeated retries. podSelector: {}, same as
    // the ingress rule, since any meshed pod can be a mesh call's source.
    Path file = NETPOL_PATH.resolve("allow-mesh-proxy.yaml");
    Map<String, Object> policy = loadSingleDocument(file);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) policy.get("spec");

    @SuppressWarnings("unchecked")
    List<String> policyTypes = (List<String>) spec.get("policyTypes");
    assertThat(policyTypes).containsExactlyInAnyOrder("Ingress", "Egress");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> egress = (List<Map<String, Object>>) spec.get("egress");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ports = (List<Map<String, Object>>) egress.get(0).get("ports");
    List<Integer> allowedPorts = ports.stream().map(p -> (Integer) p.get("port")).toList();
    assertThat(allowedPorts).containsExactly(4143);
  }

  @Test
  @DisplayName("Bootstrap installs Linkerd CRDs and control plane with a cluster-wide deny default")
  void bootstrapInstallsLinkerdWithDenyDefault() throws Exception {
    String upScript =
        Files.readString(Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/up.sh")));
    assertThat(upScript).contains("linkerd install --crds");
    assertThat(upScript).contains("--default-inbound-policy=deny");
    // Public-release hardening (TASK-LOG step 118): the trust anchor and issuer are generated
    // at bootstrap into the gitignored overlays/local/generated/, never committed.
    assertThat(upScript).contains("local-secrets.sh");
    assertThat(upScript).contains("overlays/local/generated");
    assertThat(upScript).contains("--identity-trust-anchors-file \"${generated}/linkerd-ca.crt\"");
    assertThat(upScript)
        .contains("--identity-issuer-certificate-file \"${generated}/linkerd-issuer.crt\"");
    assertThat(upScript).contains("--identity-issuer-key-file \"${generated}/linkerd-issuer.key\"");
    assertThat(upScript)
        .as("linkerd must be a required tool, the same treatment kind/kubectl already get")
        .contains("for tool in kind kubectl linkerd");
    assertThat(upScript)
        .as("a pre-2023 stable-2.x linkerd CLI must fail fast, not with a buried CronJob error")
        .contains("stable-2.*");

    // The issuer must be signed by the anchor generated next to it, and both are ECDSA P-256
    // CAs (Linkerd's requirement): the pair is regenerated together, never one file alone.
    String localSecretsScript =
        Files.readString(
            Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap/local-secrets.sh")));
    assertThat(localSecretsScript)
        .contains("prime256v1")
        .contains("basicConstraints=critical,CA:TRUE")
        .contains("basicConstraints=critical,CA:TRUE,pathlen:0")
        .contains("-CA \"${out}/linkerd-ca.crt\"")
        .contains(
            "if [ ! -s \"${out}/linkerd-ca.crt\" ] || [ ! -s \"${out}/linkerd-issuer.crt\" ]");
  }

  private java.util.Optional<Map<String, Object>> findByKindAndName(
      List<Map<String, Object>> docs, String kind, String name) {
    return docs.stream()
        .filter(d -> kind.equals(d.get("kind")))
        .filter(d -> name.equals(((Map<?, ?>) d.get("metadata")).get("name")))
        .findFirst();
  }

  private List<Map<String, Object>> loadAllDocuments(String file) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(POLICY_PATH.resolve(file).toFile())) {
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object d : yaml.loadAll(in)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) d;
        docs.add(doc);
      }
      return docs;
    }
  }

  private Map<String, Object> loadSingleDocument(Path file) throws Exception {
    Yaml yaml = new Yaml();
    try (InputStream in = new FileInputStream(file.toFile())) {
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
