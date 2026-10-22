package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.testing.PostgresFixture;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * {@link ProjectionCheckpoint} against a real PostgreSQL (row 26e, ADR-0032 §1): one row per
 * consumer, the head query, and age-based pruning.
 *
 * <p>Fixtures are reused across runs (AGENTS.md §4), so nothing here reads a total over the table.
 * Rows are advanced under fresh consumer ids, and the {@link ProjectionCheckpoint#HEAD} query — an
 * aggregate — is exercised under a fresh {@code projection_name} that no other test or run writes.
 */
@Tag("integration")
@SpringJUnitConfig(ProjectionCheckpointIntegrationTest.TestConfig.class)
class ProjectionCheckpointIntegrationTest {

  private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(5);

  @Autowired private ProjectionCheckpoint checkpoint;
  @Autowired private JdbcClient jdbc;

  @BeforeAll
  static void bootPostgres() {
    PostgresFixture.getInstance();
  }

  @Test
  @DisplayName("each consumer advances only its own row, monotonically")
  void eachConsumerOwnsItsRow() {
    var a = ProjectionCheckpoint.ConsumerRef.of("pod-" + UUID.randomUUID(), "ctag-a", "q.a");
    var b = ProjectionCheckpoint.ConsumerRef.of("pod-" + UUID.randomUUID(), "ctag-b", "q.b");

    checkpoint.advanceTo(a, "1755604800000000300");
    checkpoint.advanceTo(b, "1755604800000000100");
    // An out-of-order delivery on the same consumer never moves its row back.
    checkpoint.advanceTo(a, "1755604800000000200");

    assertThat(row(a)).isEqualTo(new BigInteger("1755604800000000300"));
    assertThat(row(b)).isEqualTo(new BigInteger("1755604800000000100"));
    assertThat(queueOf(b)).isEqualTo("q.b");
  }

  @Test
  @DisplayName("head = MIN over consumers active within the window; an idle shard does not pin it")
  void headIsMinOverActiveConsumers() {
    String name = freshProjection();
    insert(name, "busy-ahead", 300, Duration.ZERO);
    insert(name, "busy-behind", 100, Duration.ofSeconds(30));
    insert(name, "idle-shard", 50, Duration.ofMinutes(10));

    assertThat(head(name)).contains(BigInteger.valueOf(100));
  }

  @Test
  @DisplayName("head falls back to MAX over every row when every consumer is idle")
  void headFallsBackToMaxWhenAllIdle() {
    String name = freshProjection();
    insert(name, "idle-1", 300, Duration.ofMinutes(10));
    insert(name, "idle-2", 100, Duration.ofMinutes(20));

    assertThat(head(name)).contains(BigInteger.valueOf(300));
  }

  @Test
  @DisplayName("head is empty when no consumer has committed anything")
  void headIsEmptyWithNoRows() {
    assertThat(head(freshProjection())).isEmpty();
  }

  @Test
  @DisplayName("removeStale deletes rows idle past the retention and keeps recent ones")
  void removeStaleDeletesOnlyIdleRows() {
    String name = ProjectionCheckpoint.PROJECTION_NAME;
    String stale = "dead-pod-" + UUID.randomUUID() + "/ctag";
    String recent = "live-pod-" + UUID.randomUUID() + "/ctag";
    insert(name, stale, 100, Duration.ofHours(2));
    insert(name, recent, 200, Duration.ofMinutes(10));

    checkpoint.removeStale();

    assertThat(exists(stale)).as("a row idle past the 1 h retention is pruned").isFalse();
    assertThat(exists(recent)).as("a row inside the retention stays").isTrue();

    jdbc.sql("DELETE FROM projection.projection_consumer_checkpoint WHERE consumer_id = :c")
        .param("c", recent)
        .update();
  }

  @Test
  @DisplayName("removeStale keeps the highest row, so a long all-idle stall still has a head")
  void removeStaleKeepsTheHighestRow() {
    // Needs a timestamp above anything another run left under 'projection-worker'; 2^62 is far
    // past any real ledger clock (u64 nanoseconds ~ 1.8e18 today).
    String name = ProjectionCheckpoint.PROJECTION_NAME;
    String highest = "stalled-pod-" + UUID.randomUUID() + "/ctag";
    long top = 4_611_686_018_427_387_904L;
    insert(name, highest, top, Duration.ofHours(3));

    checkpoint.removeStale();

    assertThat(exists(highest)).isTrue();
    jdbc.sql("DELETE FROM projection.projection_consumer_checkpoint WHERE consumer_id = :c")
        .param("c", highest)
        .update();
  }

  // ---------------------------------------------------------------------------------------------

  private Optional<BigInteger> head(String projectionName) {
    return jdbc.sql(ProjectionCheckpoint.HEAD)
        .param("name", projectionName)
        .param("activeMillis", ACTIVE_WINDOW.toMillis())
        .query(BigInteger.class)
        .optional();
  }

  private void insert(String projectionName, String consumerId, long timestamp, Duration idle) {
    jdbc.sql(
            """
            INSERT INTO projection.projection_consumer_checkpoint
              (projection_name, consumer_id, queue_name, last_ledger_timestamp, updated_at)
            VALUES (:n, :c, 'q', :ts, now() - :idleMillis * interval '1 millisecond')
            """)
        .param("n", projectionName)
        .param("c", consumerId)
        .param("ts", timestamp)
        .param("idleMillis", idle.toMillis())
        .update();
  }

  private BigInteger row(ProjectionCheckpoint.ConsumerRef consumer) {
    return jdbc.sql(
            "SELECT last_ledger_timestamp FROM projection.projection_consumer_checkpoint"
                + " WHERE projection_name = :n AND consumer_id = :c")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .param("c", consumer.id())
        .query(BigInteger.class)
        .single();
  }

  private String queueOf(ProjectionCheckpoint.ConsumerRef consumer) {
    return jdbc.sql(
            "SELECT queue_name FROM projection.projection_consumer_checkpoint"
                + " WHERE projection_name = :n AND consumer_id = :c")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .param("c", consumer.id())
        .query(String.class)
        .single();
  }

  private boolean exists(String consumerId) {
    return jdbc.sql(
                "SELECT count(*) FROM projection.projection_consumer_checkpoint"
                    + " WHERE projection_name = :n AND consumer_id = :c")
            .param("n", ProjectionCheckpoint.PROJECTION_NAME)
            .param("c", consumerId)
            .query(Long.class)
            .single()
        > 0;
  }

  private static String freshProjection() {
    return "checkpoint-test-" + UUID.randomUUID();
  }

  @Configuration
  @EnableTransactionManagement
  @EnableConfigurationProperties(CheckpointProperties.class)
  @Import({ProjectionCheckpoint.class, CdcLagMetrics.class})
  static class TestConfig {

    @Bean
    DataSource dataSource() {
      PostgresFixture pg = PostgresFixture.getInstance();
      return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    java.time.Clock clock() {
      return dev.fondaria.common.StorableClock.systemUTC();
    }
  }
}
