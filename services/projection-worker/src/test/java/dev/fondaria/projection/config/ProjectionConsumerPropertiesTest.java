package dev.fondaria.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProjectionConsumerPropertiesTest {

  @Test
  void fillsInEveryTuningDefaultButNotTheRequiredBrokerAddress() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            null, null, null, null, null, null, 0, 0, 0, 0, null, null, null, null);

    assertThat(p.transfersExchange()).isEqualTo("ledger.transfers.v1");
    assertThat(p.entriesExchange()).isEqualTo("ledger.entries.v1");
    assertThat(p.deadLetterQueue()).isEqualTo("ledger.entries.dlq");
    assertThat(p.transfersQueue()).isEqualTo("projection.transfers.v1");
    assertThat(p.entriesQueuePrefix()).isEqualTo("projection.entries.v1");
    assertThat(p.entriesQueueCount()).isEqualTo(16);
    assertThat(p.prefetch()).isEqualTo(200);
    assertThat(p.transferConsumers()).isEqualTo(4);
    assertThat(p.batchSize()).isEqualTo(200);
    assertThat(p.batchTimeout()).isEqualTo(Duration.ofMillis(100));
    assertThat(p.retryInitialBackoff()).isEqualTo(Duration.ofSeconds(1));
    assertThat(p.retryMaxBackoff()).isEqualTo(Duration.ofSeconds(30));
    assertThat(p.deadLetterTimeout()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void keepsExplicitTransferConsumersAndBatchSettings() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            "rabbitmq",
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            6,
            50,
            Duration.ofMillis(20),
            null,
            null,
            null);

    assertThat(p.transferConsumers()).isEqualTo(6);
    assertThat(p.batchSize()).isEqualTo(50);
    assertThat(p.batchTimeout()).isEqualTo(Duration.ofMillis(20));
  }

  /**
   * ADR-0034 amendment (row 26w): one pod may own every entries queue, each with one consumer
   * holding a connection for its batch, plus the transfer consumers. Together they must leave two
   * pool connections for the cluster-halt poller and the health checks.
   */
  @Test
  void entriesQueuesPlusTransferConsumersMustLeaveTwoPoolConnectionsFree() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            "rabbitmq", null, null, null, null, null, 16, 0, 4, 0, null, null, null, null);

    p.requireConsumersFitPool(22);
    assertThatThrownBy(() -> p.requireConsumersFitPool(21))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_ENTRIES_QUEUE_COUNT")
        .hasMessageContaining("PROJECTION_TRANSFER_CONSUMERS")
        .hasMessageContaining("POSTGRES_MAX_POOL_SIZE");
  }

  /** The base configmap's values fit its pool: 16 queues + 4 transfer consumers, pool 24. */
  @Test
  void theBaseConfigmapValuesFitItsPool() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            "rabbitmq", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null);

    p.requireConsumersFitPool(24);
  }

  @Test
  void entriesQueueNamesAreThePrefixNumberedFromZero() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            "rabbitmq", null, null, null, null, "q.entries", 3, 0, 0, 0, null, null, null, null);

    assertThat(p.entriesQueueNames()).containsExactly("q.entries.0", "q.entries.1", "q.entries.2");
  }

  @Test
  void requireCompleteNamesTheAbsentVariable() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            " ", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null);

    assertThatThrownBy(p::requireComplete)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(ProjectionConsumerProperties.AMQP_HOST_VARIABLE);
  }

  @Test
  void requireCompletePassesWhenTheBrokerAddressIsPresent() {
    ProjectionConsumerProperties p =
        new ProjectionConsumerProperties(
            "rabbitmq", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null);

    assertThat(p.amqpHost()).isEqualTo("rabbitmq");
    p.requireComplete();
  }
}
