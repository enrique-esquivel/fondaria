package dev.fondaria.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.MessageProperties;
import dev.fondaria.testing.RabbitMqFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Task 30b end to end: the real {@link BridgeApplication} context against the shared RabbitMQ
 * fixture, publishing change events to the {@code tigerbeetle} exchange and asserting on what lands
 * on the RabbitMQ queues.
 */
@Tag("integration")
class BridgeIntegrationTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(BridgeIntegrationTest.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String EXCHANGE = "tigerbeetle";
  private static final String QUEUE = "bridge.ledger";
  private static final String DLQ = "bridge.ledger.dlq";
  private static final String TRANSFERS_EXCHANGE = "ledger.transfers.v1";
  private static final String ENTRIES_EXCHANGE = "ledger.entries.v1";
  private static final String ENTRIES_DLQ_QUEUE = "ledger.entries.dlq";

  private static RabbitMqFixture rabbit;
  private static Connection amqp;
  private static Channel publishChannel;
  private static HttpClient http;
  private static String authHeader;

  private ConfigurableApplicationContext bridge;

  @BeforeAll
  static void setUpClass() throws Exception {
    rabbit = RabbitMqFixture.getInstance();

    http = HttpClient.newHttpClient();
    authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (rabbit.getUsername() + ":" + rabbit.getPassword())
                        .getBytes(StandardCharsets.UTF_8));

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getAmqpPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    amqp = factory.newConnection();

    declareTopology();
    publishChannel = amqp.createChannel();
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    if (amqp != null) {
      amqp.close();
    }
  }

  @AfterEach
  void stopBridge() throws Exception {
    if (bridge != null) {
      bridge.close();
      bridge = null;
    }
    purgeQueue(QUEUE);
  }

  // -------------------------------------------------------------------------------------------
  // AC1 — one transfer produces exactly three messages (one transfer, two entries)
  // -------------------------------------------------------------------------------------------

  @Test
  void oneTransferProducesExactlyThreeMessages() throws Exception {
    bridge = startBridge(Map.of());

    String transferId = freshId();
    String debit = freshId();
    String credit = freshId();

    try (Harvest harvest = new Harvest("ac1")) {
      harvest.bindTransfers();
      harvest.bindEntries(16);
      harvest.bindDlq();

      publishChangeEvent(changeEvent(transferId, debit, credit, "150000", 484));

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                assertThat(harvest.transfers()).filteredOn(b -> b.contains(transferId)).hasSize(1);
                assertThat(harvest.entries()).filteredOn(b -> b.contains(debit)).hasSize(1);
                assertThat(harvest.entries()).filteredOn(b -> b.contains(credit)).hasSize(1);
              });

      Thread.sleep(1500);
      assertThat(harvest.transfers()).filteredOn(b -> b.contains(transferId)).hasSize(1);
      assertThat(harvest.entries()).filteredOn(b -> b.contains(transferId)).hasSize(2);
      assertThat(harvest.dlq()).filteredOn(b -> b.contains(transferId)).isEmpty();
      assertThat(
              bridge
                  .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                  .get("rabbitmq_publish_duration_seconds")
                  .tag("destination", dev.fondaria.bridge.publish.LedgerTopics.TRANSFERS)
                  .timer()
                  .count())
          .isPositive();

      String debitBody =
          harvest.entries().stream().filter(b -> b.contains(debit)).findFirst().orElseThrow();
      JsonNode debitEntry = MAPPER.readTree(debitBody);
      assertThat(debitEntry.get("direction").asText()).isEqualTo("DEBIT");
      assertThat(debitEntry.get("amount").asText()).isEqualTo("150000");
      assertThat(debitEntry.get("transferId").asText()).isEqualTo(transferId);
    }
  }

  // -------------------------------------------------------------------------------------------
  // AC2 — all events for one account land in one queue, verified across 10,000 transfers
  // -------------------------------------------------------------------------------------------

  @Test
  void allEventsForOneAccountLandInOneQueue() throws Exception {
    bridge = startBridge(Map.of("BRIDGE_AMQP_PREFETCH", "500"));

    int transferCount = 10_000;
    List<String> accounts = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      accounts.add(freshId());
    }
    Set<String> transferIds = new HashSet<>();

    try (Harvest harvest = new Harvest("ac2")) {
      harvest.bindTransfers();
      harvest.bindEntries(16);

      for (int i = 0; i < transferCount; i++) {
        String transferId = freshId();
        transferIds.add(transferId);
        String debit = accounts.get(ThreadLocalRandom.current().nextInt(accounts.size()));
        String credit;
        do {
          credit = accounts.get(ThreadLocalRandom.current().nextInt(accounts.size()));
        } while (credit.equals(debit));
        publishChangeEvent(changeEvent(transferId, debit, credit, "1", 484));
      }
      LOGGER.info("published {} change events", transferCount);

      await()
          .atMost(Duration.ofMinutes(3))
          .pollInterval(Duration.ofSeconds(2))
          .untilAsserted(
              () -> {
                int seen = 0;
                for (String id : transferIds) {
                  if (harvest.transferKeys().contains(id)) {
                    seen++;
                  }
                }
                assertThat(seen).as("every transfer message arrived").isEqualTo(transferCount);
              });

      Map<String, String> queueByAccount = new HashMap<>();
      int mine = 0;
      Set<String> accountSet = new HashSet<>(accounts);
      for (Map.Entry<String, String> entry : harvest.entryQueueAndAccount()) {
        String queue = entry.getKey();
        String account = entry.getValue();
        if (accountSet.contains(account)) {
          mine++;
          String seenQueue = queueByAccount.putIfAbsent(account, queue);
          if (seenQueue != null) {
            assertThat(queue)
                .as("account %s must always land in the same queue", account)
                .isEqualTo(seenQueue);
          }
        }
      }
      assertThat(mine).as("two entries per transfer").isEqualTo(2 * transferCount);
      assertThat(queueByAccount.values().stream().distinct().count())
          .as("the 40 accounts spread across more than one queue")
          .isGreaterThan(1);

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> assertThat(queueDepth(QUEUE)).as("queue is drained").isZero());
    }
  }

  // -------------------------------------------------------------------------------------------
  // AC3 — killing the bridge mid-batch loses nothing; duplicates are tolerated downstream
  // -------------------------------------------------------------------------------------------

  @Test
  void killingTheBridgeMidBatchLosesNothing() throws Exception {
    int transferCount = 2_000;
    Set<String> transferIds = new HashSet<>();
    for (int i = 0; i < transferCount; i++) {
      String transferId = freshId();
      transferIds.add(transferId);
      publishChangeEvent(changeEvent(transferId, freshId(), freshId(), "1", 484));
    }

    try (Harvest harvest = new Harvest("ac3")) {
      harvest.bindTransfers();

      bridge = startBridge(Map.of());
      Thread.sleep(700);
      bridge.close();
      bridge = startBridge(Map.of());

      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(
              () -> {
                Set<String> delivered = new HashSet<>();
                for (String id : transferIds) {
                  if (harvest.transferKeys().contains(id)) {
                    delivered.add(id);
                  }
                }
                assertThat(delivered)
                    .as("no transfer is lost across the restart")
                    .containsExactlyInAnyOrderElementsOf(transferIds);
              });

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> assertThat(queueDepth(QUEUE)).as("queue is drained").isZero());
    }
  }

  // -------------------------------------------------------------------------------------------
  // AC4 — producer failure does not ack the AMQP message
  // -------------------------------------------------------------------------------------------

  @Test
  void producerFailureDoesNotAckTheAmqpMessage() throws Exception {
    purgeQueue(QUEUE);

    // A broken bridge whose confirm timeout is 0s, forcing ProducerConfirmException
    ConfigurableApplicationContext broken =
        startBridge(
            Map.of(
                "fondaria.bridge.producer-confirm-timeout", "0s",
                "BRIDGE_PRODUCER_CONFIRM_TIMEOUT", "0s",
                "fondaria.bridge.producer-retry-backoff", "5s",
                "BRIDGE_PRODUCER_RETRY_BACKOFF", "5s"));
    String transferId = freshId();
    try {
      publishChangeEvent(changeEvent(transferId, freshId(), freshId(), "1", 484));
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(
              () ->
                  assertThat(queueDepth(QUEUE)).as("message still on bridge.ledger").isEqualTo(1));
      Thread.sleep(1000);
      assertThat(queueDepth(QUEUE)).as("still not acknowledged after retries").isEqualTo(1);
    } finally {
      broken.close();
    }

    try (Harvest harvest = new Harvest("ac4")) {
      harvest.bindTransfers();
      bridge = startBridge(Map.of());
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                assertThat(harvest.transfers()).anyMatch(body -> body.contains(transferId));
              });
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
  }

  // -------------------------------------------------------------------------------------------
  // A message the bridge cannot parse is preserved on the DLQ, not skipped, and is acked
  // -------------------------------------------------------------------------------------------

  @Test
  void anUnparseableMessageGoesToTheDlqAndIsAcknowledged() throws Exception {
    purgeQueue(QUEUE);
    bridge = startBridge(Map.of());

    String marker = "poison-" + freshId();
    publishRaw(
        ("{\"not\":\"a change event\",\"marker\":\"" + marker + "\"}")
            .getBytes(StandardCharsets.UTF_8));

    try (Harvest harvest = new Harvest("dlq")) {
      harvest.bindDlq();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                assertThat(harvest.dlq()).filteredOn(b -> b.contains(marker)).hasSize(1);
              });
      String dead =
          harvest.dlq().stream().filter(b -> b.contains(marker)).findFirst().orElseThrow();
      JsonNode envelope = MAPPER.readTree(dead);
      assertThat(envelope.get("reason").asText()).isEqualTo("cdc_message_unparseable");
      assertThat(envelope.get("originalBody").asText()).contains(marker);
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(queueDepth(QUEUE)).as("poison message acknowledged").isZero());
  }

  // -------------------------------------------------------------------------------------------
  // Batching: a poison message inside a burst is dead-lettered without holding back its batch
  // -------------------------------------------------------------------------------------------

  @Test
  void aPoisonMessageInsideABatchIsDeadLetteredAndItsNeighboursStillGo() throws Exception {
    purgeQueue(QUEUE);
    String marker = "poison-" + freshId();
    List<String> transferIds = new ArrayList<>();
    // Queued before the bridge starts, so the first consumer's first batch holds all of them.
    for (int i = 0; i < 20; i++) {
      String transferId = freshId();
      transferIds.add(transferId);
      publishChangeEvent(changeEvent(transferId, freshId(), freshId(), "1", 484));
      if (i == 9) {
        publishRaw(("{\"marker\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8));
      }
    }

    try (Harvest harvest = new Harvest("batch")) {
      harvest.bindTransfers();
      harvest.bindDlq();
      bridge = startBridge(Map.of("BRIDGE_AMQP_CONCURRENCY", "1"));
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                assertThat(harvest.transferKeys()).containsAll(transferIds);
                assertThat(harvest.dlq()).filteredOn(b -> b.contains(marker)).hasSize(1);
              });
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(queueDepth(QUEUE)).as("the whole batch acknowledged").isZero());
  }

  // =========================================================================================
  // bridge context
  // =========================================================================================

  private static ConfigurableApplicationContext startBridge(Map<String, String> extraEnv) {
    Map<String, Object> props = new HashMap<>();
    props.put("BRIDGE_AMQP_HOST", rabbit.getHost());
    props.put("BRIDGE_AMQP_PORT", rabbit.getAmqpPort());
    props.put("BRIDGE_AMQP_USERNAME", rabbit.getUsername());
    props.put("BRIDGE_AMQP_PASSWORD", rabbit.getPassword());
    props.put("spring.rabbitmq.host", rabbit.getHost());
    props.put("spring.rabbitmq.port", rabbit.getAmqpPort());
    props.put("spring.rabbitmq.username", rabbit.getUsername());
    props.put("spring.rabbitmq.password", rabbit.getPassword());
    props.put("server.port", "0");
    props.put("spring.main.banner-mode", "off");
    extraEnv.forEach(props::put);
    return new SpringApplicationBuilder(BridgeApplication.class).properties(props).run();
  }

  // =========================================================================================
  // Harvest helper using RabbitMQ consumer channels
  // =========================================================================================

  private static final class Harvest implements AutoCloseable {
    private final Channel channel;
    private final List<String> transfers = new CopyOnWriteArrayList<>();
    private final Set<String> transferKeys = ConcurrentHashMap.newKeySet();
    private final List<String> entries = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<String, String>> entriesWithQueue = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<String, String>> entryQueueAndAccount =
        new CopyOnWriteArrayList<>();
    private final List<String> dlq = new CopyOnWriteArrayList<>();
    private final List<String> queuesToDelete = new ArrayList<>();

    Harvest(String prefix) throws IOException {
      this.channel = amqp.createChannel();
    }

    void bindTransfers() throws IOException {
      String q = "test.transfers." + freshId();
      channel.queueDeclare(q, false, true, true, null);
      channel.queueBind(q, TRANSFERS_EXCHANGE, "");
      queuesToDelete.add(q);
      channel.basicConsume(
          q,
          true,
          new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                String consumerTag,
                Envelope envelope,
                AMQP.BasicProperties properties,
                byte[] body) {
              transfers.add(new String(body, StandardCharsets.UTF_8));
              if (envelope.getRoutingKey() != null) {
                transferKeys.add(envelope.getRoutingKey());
              }
            }
          });
    }

    void bindEntries(int numQueues) throws IOException {
      for (int i = 0; i < numQueues; i++) {
        String q = "test.entries." + i + "." + freshId();
        channel.queueDeclare(q, false, true, true, null);
        channel.queueBind(q, ENTRIES_EXCHANGE, "10"); // weight 10
        queuesToDelete.add(q);
        channel.basicConsume(
            q,
            true,
            new DefaultConsumer(channel) {
              @Override
              public void handleDelivery(
                  String consumerTag,
                  Envelope envelope,
                  AMQP.BasicProperties properties,
                  byte[] body) {
                String str = new String(body, StandardCharsets.UTF_8);
                entries.add(str);
                entriesWithQueue.add(Map.entry(q, str));
                if (envelope.getRoutingKey() != null) {
                  entryQueueAndAccount.add(Map.entry(q, envelope.getRoutingKey()));
                }
              }
            });
      }
    }

    void bindDlq() throws IOException {
      channel.queueDeclare(ENTRIES_DLQ_QUEUE, true, false, false, null);
      channel.basicConsume(
          ENTRIES_DLQ_QUEUE,
          true,
          new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                String consumerTag,
                Envelope envelope,
                AMQP.BasicProperties properties,
                byte[] body) {
              dlq.add(new String(body, StandardCharsets.UTF_8));
            }
          });
    }

    List<String> transfers() {
      return transfers;
    }

    Set<String> transferKeys() {
      return transferKeys;
    }

    List<String> entries() {
      return entries;
    }

    List<Map.Entry<String, String>> entriesWithQueue() {
      return entriesWithQueue;
    }

    List<Map.Entry<String, String>> entryQueueAndAccount() {
      return entryQueueAndAccount;
    }

    List<String> dlq() {
      return dlq;
    }

    @Override
    public void close() throws Exception {
      for (String q : queuesToDelete) {
        try {
          channel.queueDelete(q);
        } catch (Exception ignored) {
        }
      }
      channel.close();
    }
  }

  // =========================================================================================
  // RabbitMQ helpers
  // =========================================================================================

  private static void declareTopology() throws Exception {
    mgmt("DELETE", "/api/queues/%2F/" + QUEUE);
    mgmt("DELETE", "/api/queues/%2F/" + DLQ);
    mgmt("DELETE", "/api/exchanges/%2F/" + EXCHANGE);
    mgmt("DELETE", "/api/exchanges/%2F/" + TRANSFERS_EXCHANGE);
    mgmt("DELETE", "/api/exchanges/%2F/" + ENTRIES_EXCHANGE);
    try (Channel channel = amqp.createChannel()) {
      channel.exchangeDeclare(EXCHANGE, "fanout", true);
      channel.exchangeDeclare(TRANSFERS_EXCHANGE, "fanout", true);
      channel.exchangeDeclare(ENTRIES_EXCHANGE, "x-consistent-hash", true, false, Map.of());
      channel.queueDeclare(ENTRIES_DLQ_QUEUE, true, false, false, null);
      channel.queueDeclare(DLQ, true, false, false, Map.of("x-queue-type", "quorum"));
      Map<String, Object> queueArgs = new HashMap<>();
      queueArgs.put("x-queue-type", "quorum");
      queueArgs.put("x-dead-letter-exchange", "");
      queueArgs.put("x-dead-letter-routing-key", DLQ);
      queueArgs.put("x-delivery-limit", 5);
      channel.queueDeclare(QUEUE, true, false, false, queueArgs);
      channel.queueBind(QUEUE, EXCHANGE, "");
    }
  }

  private static void publishChangeEvent(String body) throws Exception {
    publishRaw(body.getBytes(StandardCharsets.UTF_8));
  }

  private static synchronized void publishRaw(byte[] body) throws Exception {
    publishChannel.basicPublish(EXCHANGE, "", MessageProperties.PERSISTENT_TEXT_PLAIN, body);
  }

  private static void purgeQueue(String queue) throws Exception {
    try (Channel ch = amqp.createChannel()) {
      ch.queuePurge(queue);
    } catch (Exception ignored) {
      mgmt("DELETE", "/api/queues/%2F/" + queue + "/contents");
    }
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(queueDepth(queue)).isZero());
  }

  private static int queueDepth(String queue) throws Exception {
    HttpResponse<String> response = mgmt("GET", "/api/queues/%2F/" + queue);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    JsonNode msgNode = body.get("messages");
    if (msgNode != null && !msgNode.isNull()) {
      return msgNode.asInt();
    }
    int ready = body.has("messages_ready") ? body.get("messages_ready").asInt() : 0;
    int unack =
        body.has("messages_unacknowledged") ? body.get("messages_unacknowledged").asInt() : 0;
    return ready + unack;
  }

  private static HttpResponse<String> mgmt(String method, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://" + rabbit.getHost() + ":" + rabbit.getManagementPort() + path))
            .header("Authorization", authHeader)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String freshId() {
    return Long.toUnsignedString(
        ThreadLocalRandom.current().nextLong(100_000_000_000_000_000L, Long.MAX_VALUE));
  }

  private static String changeEvent(
      String transferId, String debitId, String creditId, String amount, long ledger) {
    return "{\"timestamp\":\""
        + (System.currentTimeMillis() * 1_000_000L)
        + "\",\"type\":\"single_phase\",\"ledger\":"
        + ledger
        + ",\"transfer\":{\"id\":\""
        + transferId
        + "\",\"amount\":\""
        + amount
        + "\",\"pending_id\":\"0\",\"user_data_128\":\"0\",\"user_data_64\":\"0\","
        + "\"user_data_32\":0,\"timeout\":0,\"code\":10,\"flags\":0,\"timestamp\":\"1\"},"
        + "\"debit_account\":{\"id\":\""
        + debitId
        + "\",\"debits_pending\":\"0\",\"debits_posted\":\""
        + amount
        + "\",\"credits_pending\":\"0\",\"credits_posted\":\"0\",\"user_data_128\":\"0\","
        + "\"user_data_64\":\"0\",\"user_data_32\":0,\"code\":1,\"flags\":0,\"timestamp\":\"1\"},"
        + "\"credit_account\":{\"id\":\""
        + creditId
        + "\",\"debits_pending\":\"0\",\"debits_posted\":\"0\",\"credits_pending\":\"0\","
        + "\"credits_posted\":\""
        + amount
        + "\",\"user_data_128\":\"0\",\"user_data_64\":\"0\",\"user_data_32\":0,\"code\":1,"
        + "\"flags\":0,\"timestamp\":\"1\"}}";
  }
}
