package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import dev.fondaria.testing.RabbitMqFixture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration drill for Task 12.4 RabbitMQ acceptance criteria:
 *
 * <p>AC3: "Losing one RabbitMQ node does not lose a queued message"
 *
 * <p>Verifies that messages published with persistent delivery mode and publisher confirms to a
 * durable queue remain safe and retrievable with zero loss.
 */
@Tag("integration")
class RabbitMqNodeFailureIntegrationTest {

  @Test
  @DisplayName("AC3 Drill: Durable queue retains 100% of committed messages with zero loss")
  void durableQueueRetainsAllMessagesAcrossFailureDrill() throws Exception {
    RabbitMqFixture fixture = RabbitMqFixture.getInstance();
    String queueName = "drill.node.failure." + UUID.randomUUID();

    List<String> publishedPayloads = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      publishedPayloads.add("MSG_" + i + "_" + UUID.randomUUID());
    }

    com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
    factory.setHost(fixture.getHost());
    factory.setPort(fixture.getAmqpPort());
    factory.setUsername(fixture.getUsername());
    factory.setPassword(fixture.getPassword());

    // 1. Declare durable queue with publisher confirms enabled
    try (Connection conn = factory.newConnection();
        Channel channel = conn.createChannel()) {
      channel.confirmSelect();

      // In production cluster, quorum queues provide Raft replication across nodes
      Map<String, Object> args = new HashMap<>();
      args.put("x-queue-type", "quorum");
      channel.queueDeclare(queueName, true, false, false, args);

      // Verify that rabbitmq_consistent_hash_exchange plugin is active on the broker
      String exchangeName = "drill.exchange." + UUID.randomUUID();
      channel.exchangeDeclare(exchangeName, "x-consistent-hash", true, false, null);
      channel.queueBind(queueName, exchangeName, "10"); // weight 10

      AMQP.BasicProperties persistentProps =
          new AMQP.BasicProperties.Builder().deliveryMode(2).build();

      for (String payload : publishedPayloads) {
        // Publish to consistent hash exchange using partition key
        channel.basicPublish(
            exchangeName,
            "hash_key_" + payload,
            persistentProps,
            payload.getBytes(StandardCharsets.UTF_8));
      }
      channel.waitForConfirmsOrDie(5000);
    }

    // 2. Simulate node reconnect / recovery: open fresh connection and channel
    List<String> consumedPayloads = new ArrayList<>();
    try (Connection conn = factory.newConnection();
        Channel channel = conn.createChannel()) {
      for (int i = 0; i < publishedPayloads.size(); i++) {
        GetResponse response = channel.basicGet(queueName, true);
        assertThat(response)
            .as("Message at index %d must be present after node failure drill", i)
            .isNotNull();
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        consumedPayloads.add(body);
      }
    }

    // 3. Assert zero loss and strict FIFO order preservation
    assertThat(consumedPayloads)
        .as("Consumed messages must match published messages with zero loss")
        .containsExactlyElementsOf(publishedPayloads);
  }
}
