package dev.fondaria.demo.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real client against a JDK {@link HttpServer} stub -- no framework and no
 * Testcontainers needed for this module's own HTTP plumbing (`docs/ENGINEERING-RULES.md` §4: this
 * module names no new dependency, and {@code com.sun.net.httpserver} ships with the JDK).
 */
class DemoHttpClientTest {

  private HttpServer server;
  private DemoHttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setExecutor(null);
    baseUrl = "http://localhost:" + server.getAddress().getPort();
    client = new DemoHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    client.close();
  }

  @Test
  void postJsonReturnsTheParsedBody() throws IOException {
    server.createContext(
        "/v1/organizations",
        exchange -> {
          byte[] response =
              "{\"organizationId\":\"org-1\",\"revision\":1}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    JsonResponse response =
        client.postJson(URI.create(baseUrl + "/v1/organizations"), Map.of("legalName", "Acme"));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.body().get("organizationId")).isEqualTo("org-1");
  }

  @Test
  void getJsonReturnsTheParsedBody() throws IOException {
    server.createContext(
        "/v1/funds/f1",
        exchange -> {
          byte[] response = "{\"fundId\":\"f1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    JsonResponse response = client.getJson(URI.create(baseUrl + "/v1/funds/f1"));
    assertThat(response.body().get("fundId")).isEqualTo("f1");
  }

  @Test
  void errorStatusThrowsWithTheProblemDetailBody() throws IOException {
    server.createContext(
        "/v1/funds",
        exchange -> {
          byte[] response =
              "{\"title\":\"Conflict\",\"status\":409}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(409, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    assertThatThrownBy(() -> client.postJson(URI.create(baseUrl + "/v1/funds"), Map.of()))
        .isInstanceOf(DemoHttpException.class)
        .satisfies(
            e -> {
              DemoHttpException httpException = (DemoHttpException) e;
              assertThat(httpException.status()).isEqualTo(409);
              assertThat(httpException.body()).contains("Conflict");
            });
  }

  @Test
  void putBytesReturnsTheStatusCode() throws IOException {
    server.createContext(
        "/upload",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    int status =
        client.putBytes(
            URI.create(baseUrl + "/upload"), "file-bytes".getBytes(StandardCharsets.UTF_8));
    assertThat(status).isEqualTo(200);
  }

  @Test
  void unreachableHostThrowsADemoHttpException() {
    client.close();
    DemoHttpClient unreachable = new DemoHttpClient(Duration.ofMillis(200), Duration.ofMillis(200));
    assertThatThrownBy(() -> unreachable.getJson(URI.create("http://127.0.0.1:1")))
        .isInstanceOf(DemoHttpException.class);
    unreachable.close();
  }
}
