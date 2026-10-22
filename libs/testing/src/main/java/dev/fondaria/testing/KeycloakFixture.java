package dev.fondaria.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Singleton fixture for Keycloak container connected to PostgreSQL (keycloak schema) and pre-loaded
 * with infrastructure/keycloak/realm.json (Task 9.1).
 */
public final class KeycloakFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakFixture.class);
  private static volatile KeycloakFixture instance;

  public static final String REALM_NAME = "fondaria";
  public static final String ADMIN_USER = "admin";
  public static final String ADMIN_PASSWORD = "admin_dev_pass";

  private final GenericContainer<?> container;
  private final PostgresFixture postgres;
  private final HttpClient httpClient;

  private KeycloakFixture() {
    ContainerImages.initDockerEnv();
    this.postgres = PostgresFixture.getInstance();
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    String postgresBridgeIp = bridgeIp(postgres.getContainerId());

    try (var conn =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var stmt = conn.createStatement()) {
      stmt.execute("DROP SCHEMA IF EXISTS keycloak CASCADE; CREATE SCHEMA keycloak;");
    } catch (java.sql.SQLException e) {
      LOGGER.warn("Could not reset keycloak schema: {}", e.getMessage());
    }

    Path realmPath = Path.of("infrastructure/keycloak/realm.json");
    if (!Files.exists(realmPath)) {
      Path candidate = Path.of("../../infrastructure/keycloak/realm.json");
      if (Files.exists(candidate)) {
        realmPath = candidate;
      }
    }

    LOGGER.info("Starting Keycloak container with image {}", ContainerImages.KEYCLOAK);
    this.container =
        new GenericContainer<>(DockerImageName.parse(ContainerImages.KEYCLOAK))
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                MountableFile.forHostPath(realmPath.toAbsolutePath().toString()),
                "/opt/keycloak/data/import/realm.json")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", ADMIN_USER)
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("KC_HTTP_PORT", "8080")
            .withEnv("KC_DB", "postgres")
            .withEnv("KC_DB_URL_HOST", postgresBridgeIp)
            .withEnv("KC_DB_URL_PORT", "5432")
            .withEnv("KC_DB_URL_DATABASE", "funds")
            .withEnv("KC_DB_SCHEMA", "keycloak")
            .withEnv("KC_DB_USERNAME", "funds")
            .withEnv("KC_DB_PASSWORD", "funds_dev_pass")
            .waitingFor(
                Wait.forHttp("/realms/" + REALM_NAME)
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)))
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("keycloak"));

    this.container.start();
    LOGGER.info("Keycloak container started at {}", getAuthServerUrl());
  }

  public static KeycloakFixture getInstance() {
    if (instance == null) {
      synchronized (KeycloakFixture.class) {
        if (instance == null) {
          instance = new KeycloakFixture();
        }
      }
    }
    return instance;
  }

  public String getHost() {
    return container.getHost();
  }

  public int getPort() {
    return container.getMappedPort(8080);
  }

  public String getAuthServerUrl() {
    return "http://" + getHost() + ":" + getPort();
  }

  public String getRealmUrl() {
    return getAuthServerUrl() + "/realms/" + REALM_NAME;
  }

  public String getTokenEndpoint() {
    return getRealmUrl() + "/protocol/openid-connect/token";
  }

  public String getAuthEndpoint() {
    return getRealmUrl() + "/protocol/openid-connect/auth";
  }

  public String getJwksUrl() {
    return getRealmUrl() + "/protocol/openid-connect/certs";
  }

  public String getOpenIdConfigurationUrl() {
    return getRealmUrl() + "/.well-known/openid-configuration";
  }

  public String getIssuer() {
    return getRealmUrl();
  }

  public org.testcontainers.containers.Container.ExecResult execInContainer(String... command)
      throws IOException, InterruptedException {
    return container.execInContainer(command);
  }

  public String getContainerId() {
    return container.getContainerId();
  }

  /** Fetches an admin access token for Keycloak Admin REST API operations. */
  public String getAdminAccessToken() {
    String form =
        "grant_type=password&client_id=admin-cli&username="
            + URLEncoder.encode(ADMIN_USER, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getAuthServerUrl() + "/realms/master/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Failed to get admin access token: " + response.body());
      }
      return extractJsonString(response.body(), "access_token");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static String extractJsonString(String json, String key) {
    String pattern = "\"" + key + "\":\"";
    int start = json.indexOf(pattern);
    if (start == -1) {
      pattern = "\"" + key + "\" : \"";
      start = json.indexOf(pattern);
    }
    if (start == -1) {
      throw new IllegalArgumentException("Key not found in json: " + key);
    }
    start += pattern.length();
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static String bridgeIp(String containerId) {
    return DockerClientFactory.instance()
        .client()
        .inspectContainerCmd(containerId)
        .exec()
        .getNetworkSettings()
        .getNetworks()
        .values()
        .stream()
        .map(network -> network.getIpAddress())
        .filter(ip -> ip != null && !ip.isBlank())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no IP for container " + containerId));
  }
}
