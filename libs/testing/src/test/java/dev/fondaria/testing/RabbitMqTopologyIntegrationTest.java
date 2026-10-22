package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 4.1 — RabbitMQ topology. Runs the real {@code declare-topology.sh} against a live broker
 * (never a reimplementation of it in Java) and asserts on the broker's own state afterwards.
 */
@Tag("integration")
class RabbitMqTopologyIntegrationTest {

  private static final Path SCRIPT_PATH =
      findRepoRoot().resolve("infrastructure/compose/rabbitmq/declare-topology.sh");

  private RabbitMqFixture rabbit;
  private HttpClient http;
  private String authHeader;

  @BeforeEach
  void setUp() {
    rabbit = RabbitMqFixture.getInstance();
    rabbit.reset();
    // HTTP/1.1 explicitly: the default client's HTTP/2 upgrade attempt against RabbitMQ's
    // plain HTTP/1.1 management API intermittently corrupts POST/PUT bodies (observed as an
    // EOFException from Http2Connection deep in the JDK internals) — the management API never
    // supports h2c, so there is nothing to upgrade to.
    http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (rabbit.getUsername() + ":" + rabbit.getPassword())
                        .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void appliesCleanlyToAnEmptyBrokerAndIsIdempotentOnReapply() throws Exception {
    assertThat(runScript(Map.of())).as("first apply").isEqualTo(0);

    Map<String, Object> exchange = getJson("/api/exchanges/%2F/tigerbeetle");
    assertThat(exchange.get("type")).isEqualTo("fanout");
    assertThat(exchange.get("durable")).isEqualTo(true);

    Map<String, Object> ledgerQueue = getJson("/api/queues/%2F/bridge.ledger");
    assertThat(ledgerQueue.get("durable")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> ledgerArgs = (Map<String, Object>) ledgerQueue.get("arguments");
    assertThat(ledgerArgs.get("x-queue-type")).isEqualTo("quorum");
    assertThat(ledgerArgs.get("x-dead-letter-routing-key")).isEqualTo("bridge.ledger.dlq");
    assertThat(((Number) ledgerArgs.get("x-delivery-limit")).intValue()).isEqualTo(5);

    Map<String, Object> dlq = getJson("/api/queues/%2F/bridge.ledger.dlq");
    assertThat(dlq.get("durable")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> dlqArgs = (Map<String, Object>) dlq.get("arguments");
    assertThat(dlqArgs.get("x-queue-type")).isEqualTo("quorum");

    List<Map<String, Object>> bindings = getJsonList("/api/queues/%2F/bridge.ledger/bindings");
    assertThat(bindings)
        .as("bridge.ledger is bound to the tigerbeetle exchange")
        .anyMatch(b -> "tigerbeetle".equals(b.get("source")));

    assertThat(runScript(Map.of())).as("reapply is a no-op, not an error").isEqualTo(0);
  }

  @Test
  void redeclaringWithDifferentArgumentsFailsLoudlyRatherThanDivergingSilently() throws Exception {
    // A classic queue, not the quorum one the script would declare. RabbitMQ's own
    // queue.declare rejects this as "inequivalent arg" rather than us detecting a diff.
    putJson("/api/queues/%2F/bridge.ledger", Map.of("durable", true, "arguments", Map.of()));

    assertThat(runScript(Map.of()))
        .as("conflicting redeclare must fail, not silently coexist")
        .isNotEqualTo(0);
  }

  @Test
  void aMessageFailingRepeatedlyLandsInTheDlq() throws Exception {
    int deliveryLimit = 2;
    assertThat(
            runScript(
                Map.of("RABBITMQ_BRIDGE_LEDGER_DELIVERY_LIMIT", String.valueOf(deliveryLimit))))
        .isEqualTo(0);

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getAmqpPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.basicPublish(
          "tigerbeetle",
          "",
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          "poison".getBytes(StandardCharsets.UTF_8));
    }

    // Redeliver via the management API's own get+reject, not a native AMQP consumer that
    // immediately re-nacks: a tight nack-and-immediately-redeliver loop to a single standing
    // consumer measurably never tripped x-delivery-limit in this broker (observed past 8,000
    // redeliveries), while a get-then-reject cycle — closing the "delivery" out each time,
    // exactly what a normal consumer with real processing time between messages does — does.
    // Never counted or stopped early on this side; RabbitMQ decides when the limit is
    // exceeded, this only keeps offering the message back until it does.
    int deliveries = 0;
    List<Object> got;
    do {
      got = mgmtQueueGet("bridge.ledger", "reject_requeue_true");
      if (!got.isEmpty()) {
        deliveries++;
      }
    } while (!got.isEmpty() && deliveries < deliveryLimit + 10);

    assertThat(deliveries).as("redelivery count").isGreaterThanOrEqualTo(deliveryLimit + 1);
    assertThat(got).as("bridge.ledger drops the message once the limit is exceeded").isEmpty();

    List<Object> deadLettered = mgmtQueueGet("bridge.ledger.dlq", "ack_requeue_false");
    assertThat(deadLettered).as("the poison message landed in the DLQ").hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> message = (Map<String, Object>) deadLettered.get(0);
    assertThat(message.get("payload")).isEqualTo("poison");
  }

  @SuppressWarnings("unchecked")
  private List<Object> mgmtQueueGet(String queue, String ackmode)
      throws IOException, InterruptedException {
    String body = toJson(Map.of("count", 1, "ackmode", ackmode, "encoding", "auto"));
    HttpResponse<String> response = send("POST", "/api/queues/%2F/" + queue + "/get", body);
    assertThat(response.statusCode()).as(queue).isEqualTo(200);
    return (List<Object>) new Yaml().load(response.body());
  }

  private int runScript(Map<String, String> extraEnv) throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder("/bin/sh", SCRIPT_PATH.toString()).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put(
        "RABBITMQ_MANAGEMENT_URL", "http://" + rabbit.getHost() + ":" + rabbit.getManagementPort());
    env.put("RABBITMQ_DEFAULT_USER", rabbit.getUsername());
    env.put("RABBITMQ_DEFAULT_PASS", rabbit.getPassword());
    env.putAll(extraEnv);

    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      System.err.println("declare-topology.sh output:\n" + output);
    }
    return exitCode;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String path) throws IOException, InterruptedException {
    HttpResponse<String> response = send("GET", path, null);
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    return (Map<String, Object>) new Yaml().load(response.body());
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getJsonList(String path)
      throws IOException, InterruptedException {
    HttpResponse<String> response = send("GET", path, null);
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    return (List<Map<String, Object>>) new Yaml().load(response.body());
  }

  private void putJson(String path, Map<String, Object> body)
      throws IOException, InterruptedException {
    HttpResponse<String> response = send("PUT", path, toJson(body));
    assertThat(response.statusCode()).as(path).isIn(200, 201, 204);
  }

  private HttpResponse<String> send(String method, String path, String jsonBody)
      throws IOException, InterruptedException {
    HttpRequest.BodyPublisher publisher =
        jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://" + rabbit.getHost() + ":" + rabbit.getManagementPort() + path))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // Minimal hand-rolled encoder: this test's request bodies are fixed, flat maps of
  // strings/booleans, and snakeyaml (already a dependency) reads JSON but does not write it.
  private String toJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(entry.getKey()).append("\":").append(toJsonValue(entry.getValue()));
    }
    return sb.append('}').toString();
  }

  @SuppressWarnings("unchecked")
  private String toJsonValue(Object value) {
    if (value instanceof Map) {
      return toJson((Map<String, Object>) value);
    }
    if (value instanceof Boolean || value instanceof Number) {
      return String.valueOf(value);
    }
    return '"' + String.valueOf(value) + '"';
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("infrastructure/compose/docker-compose.yml"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from " + Paths.get(""));
  }
}
