package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.projection.ProjectionWorkerApplication;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The projection wired end to end (Tasks 5.4 and 5.5, ADR-0022): a message produced to RabbitMQ is
 * decoded by {@link LedgerEventListener}, projected by the real {@link JdbcProjectionSink}, and the
 * RabbitMQ message is acknowledged only after the row is durable. Covers both a transfer ({@code
 * projection.transfer}) and a unit-holding entry ({@code projection.participant_units}).
 */
@Tag("integration")
class TransferProjectionEndToEndIntegrationTest {

  private static RabbitMqFixture rabbit;

  private String transfersExchange;
  private String entriesExchange;
  private String deadLetterQueue;
  private String transfersQueue;
  private String entriesQueuePrefix;
  private ConfigurableApplicationContext context;

  @BeforeAll
  static void startFixtures() {
    PostgresFixture.getInstance();
    rabbit = RabbitMqFixture.getInstance();
  }

  @AfterEach
  void stop() {
    if (context != null) {
      context.close();
      context = null;
    }
  }

  @Test
  @DisplayName(
      "a transfer produced to RabbitMQ lands in projection.transfer and advances the cursor")
  void transferIsProjectedEndToEnd() {
    String nonce = UUID.randomUUID().toString().substring(0, 8);
    transfersExchange = "test.transfers." + nonce;
    entriesExchange = "test.entries." + nonce;
    deadLetterQueue = "test.dlq." + nonce;
    transfersQueue = "test.proj.transfers." + nonce;
    entriesQueuePrefix = "test.proj.entries." + nonce;
    createTopology();

    context = start();

    String tenant =
        TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()))
            .asUnsignedDecimal();
    BigInteger transferId = new BigInteger(96, new java.util.Random()).add(BigInteger.ONE);
    String timestampNanos = "1755604800000000000";
    produce(
        transfersExchange,
        transferId.toString(),
        "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":484,"
            + "\"timestamp\":\""
            + timestampNanos
            + "\",\"transferId\":\""
            + transferId
            + "\",\"amount\":\"125000\",\"pendingId\":\"0\",\"transferCode\":1,\"transferFlags\":0,"
            + "\"transferTimestamp\":\""
            + timestampNanos
            + "\",\"userData128\":\"0\",\"userData64\":\""
            + tenant
            + "\",\"userData32\":0,\"debitAccountId\":\"700000000000000010\","
            + "\"creditAccountId\":\"700000000000000020\",\"debitAccountCode\":1,"
            + "\"creditAccountCode\":2}");

    JdbcClient jdbc = context.getBean(JdbcClient.class);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              Long count =
                  jdbc.sql(
                          "SELECT count(*) FROM projection.transfer WHERE tenant_id = :t"
                              + " AND transfer_id = :id")
                      .param("t", tenant)
                      .param("id", transferId)
                      .query(Long.class)
                      .single();
              assertThat(count).isEqualTo(1L);
            });

    Map<String, Object> row =
        jdbc.sql("SELECT * FROM projection.transfer WHERE tenant_id = :t AND transfer_id = :id")
            .param("t", tenant)
            .param("id", transferId)
            .query()
            .singleRow();
    assertThat(((Number) row.get("amount_scaled")).longValue()).isEqualTo(125_000L);

    BigInteger checkpoint =
        jdbc.sql(
                "SELECT max(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint"
                    + " WHERE projection_name = :n")
            .param("n", ProjectionCheckpoint.PROJECTION_NAME)
            .query(BigInteger.class)
            .single();
    assertThat(checkpoint).isGreaterThanOrEqualTo(new BigInteger(timestampNanos));
  }

  @Test
  @DisplayName("a unit-holding entry produced to RabbitMQ lands in projection.participant_units")
  void unitHoldingIsProjectedEndToEnd() {
    String nonce = UUID.randomUUID().toString().substring(0, 8);
    transfersExchange = "test.transfers." + nonce;
    entriesExchange = "test.entries." + nonce;
    deadLetterQueue = "test.dlq." + nonce;
    transfersQueue = "test.proj.transfers." + nonce;
    entriesQueuePrefix = "test.proj.entries." + nonce;
    createTopology();

    context = start();

    String tenant =
        TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()))
            .asUnsignedDecimal();
    BigInteger accountId = new BigInteger(96, new java.util.Random()).add(BigInteger.ONE);
    BigInteger transferId = new BigInteger(96, new java.util.Random()).add(BigInteger.ONE);
    String timestampNanos = "1755604800000000000";
    produce(
        entriesExchange,
        accountId.toString(),
        "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":5001,\"timestamp\":\""
            + timestampNanos
            + "\",\"transferId\":\""
            + transferId
            + "\",\"transferCode\":2,\"transferTimestamp\":\""
            + timestampNanos
            + "\",\"amount\":\"1\",\"direction\":\"CREDIT\",\"accountId\":\""
            + accountId
            + "\",\"accountCode\":102,\"accountFlags\":0,\"debitsPending\":\"0\","
            + "\"debitsPosted\":\"0\",\"creditsPending\":\"0\",\"creditsPosted\":\"30000000000\","
            + "\"accountUserData128\":\"0\",\"accountUserData64\":\""
            + tenant
            + "\",\"accountUserData32\":0,\"pendingId\":\"0\"}");

    JdbcClient jdbc = context.getBean(JdbcClient.class);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              Long units =
                  jdbc.sql(
                          "SELECT units_scaled FROM projection.participant_units"
                              + " WHERE tenant_id = :t AND participant_id = :p")
                      .param("t", tenant)
                      .param("p", accountId)
                      .query(Long.class)
                      .optional()
                      .orElse(null);
              assertThat(units).isEqualTo(30_000_000_000L);
            });
  }

  private void createTopology() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.exchangeDeclare(transfersExchange, "fanout", true);
      channel.exchangeDeclare(entriesExchange, "x-consistent-hash", true, false, Map.of());
      channel.queueDeclare(deadLetterQueue, true, false, false, null);
      channel.queueDeclare(transfersQueue, true, false, false, null);
      channel.queueBind(transfersQueue, transfersExchange, "");
      for (int i = 0; i < 4; i++) {
        String q = entriesQueuePrefix + "." + i;
        channel.queueDeclare(q, true, false, false, null);
        channel.queueBind(q, entriesExchange, "10");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private ConfigurableApplicationContext start() {
    PostgresFixture pg = PostgresFixture.getInstance();
    Map<String, Object> props = new java.util.HashMap<>();
    props.put("PROJECTION_AMQP_HOST", rabbit.getHost());
    props.put("PROJECTION_AMQP_PORT", rabbit.getPort());
    props.put("PROJECTION_AMQP_USERNAME", rabbit.getUsername());
    props.put("PROJECTION_AMQP_PASSWORD", rabbit.getPassword());
    props.put("PROJECTION_TRANSFERS_EXCHANGE", transfersExchange);
    props.put("PROJECTION_ENTRIES_EXCHANGE", entriesExchange);
    props.put("PROJECTION_DEAD_LETTER_QUEUE", deadLetterQueue);
    props.put("PROJECTION_TRANSFERS_QUEUE", transfersQueue);
    props.put("PROJECTION_ENTRIES_QUEUE_PREFIX", entriesQueuePrefix);
    props.put("PROJECTION_ENTRIES_QUEUE_COUNT", "4");
    // A halt is a row in the reused database: a fresh name per context means one this run
    // raises can never stop a later run's worker (ADR-0032 §2).
    props.put("fondaria.projection.halt.projection-name", "test-" + java.util.UUID.randomUUID());
    props.put("POSTGRES_URL", pg.getJdbcUrl());
    props.put("POSTGRES_USER", pg.getUsername());
    props.put("POSTGRES_PASSWORD", pg.getPassword());
    props.put("PROJECTION_PREFETCH", "10");
    props.put("server.port", "0");
    props.put("spring.main.banner-mode", "off");
    return new SpringApplicationBuilder(ProjectionWorkerApplication.class).properties(props).run();
  }

  private void produce(String exchange, String key, String value) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.basicPublish(
          exchange,
          key,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
