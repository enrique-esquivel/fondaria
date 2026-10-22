package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * One realm definition, loaded three ways: {@code infrastructure/keycloak/realm.json} (compose and
 * the Testcontainers-launched Keycloak in {@link KeycloakRealmIntegrationTest}), the base ConfigMap
 * {@code infrastructure/k8s/base/keycloak/configmap.yaml} (every cluster), and the local overlay's
 * demo users file. The base realm carries no credentials: no human users and client secrets as
 * {@code ${VAR}} placeholders. This test checks the pieces still add up -- base realm, with the
 * local overlay's client secrets substituted and its users file appended, is exactly realm.json --
 * so a realm change made in one place and not the other fails here.
 *
 * <p>Before the public-release hardening the ConfigMap embedded realm.json verbatim, demo passwords
 * included, and this test compared the two byte for byte (TASK-LOG step 118).
 */
class KeycloakRealmConfigMapParityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Placeholder in the base realm, by client id, and the local Secret key that fills it. */
  private static final Map<String, String> PLACEHOLDERS =
      Map.of(
          "employer-portal", "${EMPLOYER_PORTAL_CLIENT_SECRET}",
          "internal-services", "${INTERNAL_SERVICES_CLIENT_SECRET}");

  @Test
  @DisplayName("The base realm has no user credentials and only placeholder client secrets")
  void baseRealmCarriesNoCredentials() throws Exception {
    JsonNode base = baseRealm();

    for (JsonNode user : base.path("users")) {
      assertThat(user.has("credentials"))
          .as("base realm user %s must carry no credentials", user.path("username").asText())
          .isFalse();
    }
    assertThat(base.path("users"))
        .as("the only base user is the internal-services service account (no password)")
        .extracting(u -> u.path("username").asText())
        .containsExactly("service-account-internal-services");

    for (JsonNode client : base.path("clients")) {
      if (client.has("secret")) {
        assertThat(client.path("secret").asText())
            .as("client %s's secret must be an environment placeholder", client.path("clientId"))
            .isEqualTo(PLACEHOLDERS.get(client.path("clientId").asText()));
      }
    }
  }

  @Test
  @DisplayName("Keycloak resolves every client-secret placeholder from a required Secret")
  void placeholdersAreFilledFromRequiredSecretKeyRefs() throws Exception {
    Map<String, Object> deployment =
        loadYaml(RepositoryRoot.path("infrastructure/k8s/base/keycloak/deployment.yaml")).get(0);
    List<Map<String, Object>> env = containerEnv(deployment);

    for (String placeholder : PLACEHOLDERS.values()) {
      String variable = placeholder.substring(2, placeholder.length() - 1);
      Map<String, Object> entry =
          env.stream().filter(e -> variable.equals(e.get("name"))).findFirst().orElse(null);
      assertThat(entry).as("keycloak env %s", variable).isNotNull();
      @SuppressWarnings("unchecked")
      Map<String, Object> ref =
          (Map<String, Object>) ((Map<String, Object>) entry.get("valueFrom")).get("secretKeyRef");
      assertThat(ref.get("name")).isEqualTo("keycloak-client-secrets");
      assertThat(ref.get("optional"))
          .as("%s must not be optional -- production fails closed without the Secret", variable)
          .isNotEqualTo(Boolean.TRUE);
    }
  }

  @Test
  @DisplayName("Base realm + local client secrets + local demo users == infrastructure/keycloak")
  void baseRealmPlusLocalOverlayIsTheLocalRealm() throws Exception {
    JsonNode source =
        MAPPER.readTree(
            Files.readString(Path.of(RepositoryRoot.path("infrastructure/keycloak/realm.json"))));

    String baseText = baseRealmText();
    Map<String, String> localSecrets = localClientSecrets();
    for (Map.Entry<String, String> placeholder : PLACEHOLDERS.entrySet()) {
      String value = localSecrets.get(placeholder.getKey());
      assertThat(value)
          .as("local overlay must generate keycloak-client-secrets/%s", placeholder.getKey())
          .isNotNull();
      baseText = baseText.replace(placeholder.getValue(), value);
    }
    ObjectNode assembled = (ObjectNode) MAPPER.readTree(baseText);

    JsonNode usersFile =
        MAPPER.readTree(
            Files.readString(
                Path.of(
                    RepositoryRoot.path(
                        "infrastructure/k8s/overlays/local/keycloak/fondaria-users-0.json"))));
    assertThat(usersFile.path("realm").asText()).isEqualTo("fondaria");

    // Directory import adds the users file's users after the realm's own; realm.json lists
    // the demo users first and the service account last, so rebuild that order.
    ArrayNode users = MAPPER.createArrayNode();
    usersFile.path("users").forEach(users::add);
    assembled.path("users").forEach(users::add);
    assembled.set("users", users);

    assertThat((JsonNode) assembled)
        .as(
            "base/keycloak/configmap.yaml + overlays/local (keycloak-client-secrets, "
                + "keycloak/fondaria-users-0.json) must reproduce infrastructure/keycloak/realm.json "
                + "-- a realm change made to one and not the other means the cluster runs a "
                + "different realm than the one Task 9.1's integration test verifies")
        .isEqualTo(source);
  }

  private static JsonNode baseRealm() throws Exception {
    return MAPPER.readTree(baseRealmText());
  }

  private static String baseRealmText() throws Exception {
    Map<String, Object> configMap =
        loadYaml(RepositoryRoot.path("infrastructure/k8s/base/keycloak/configmap.yaml")).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) configMap.get("data");
    String realm = (String) data.get("fondaria-realm.json");
    assertThat(realm)
        .as(
            "configmap.yaml must embed fondaria-realm.json (the name directory import pairs users to)")
        .isNotNull();
    return realm;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> localClientSecrets() throws Exception {
    Map<String, Object> kustomization =
        loadYaml(RepositoryRoot.path("infrastructure/k8s/overlays/local/kustomization.yaml"))
            .get(0);
    Map<String, String> secrets = new HashMap<>();
    for (Map<String, Object> generator :
        (List<Map<String, Object>>) kustomization.get("secretGenerator")) {
      if ("keycloak-client-secrets".equals(generator.get("name"))) {
        for (String literal : (List<String>) generator.get("literals")) {
          int separator = literal.indexOf('=');
          secrets.put(literal.substring(0, separator), literal.substring(separator + 1));
        }
      }
    }
    return secrets;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> containerEnv(Map<String, Object> deployment) {
    Map<String, Object> spec = (Map<String, Object>) deployment.get("spec");
    Map<String, Object> template = (Map<String, Object>) spec.get("template");
    Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
    List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
    return (List<Map<String, Object>>) containers.get(0).get("env");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> loadYaml(String path) throws Exception {
    try (InputStream in = new FileInputStream(path)) {
      List<Map<String, Object>> docs = new ArrayList<>();
      for (Object doc : new Yaml().loadAll(in)) {
        if (doc instanceof Map<?, ?>) {
          docs.add((Map<String, Object>) doc);
        }
      }
      return docs;
    }
  }
}
