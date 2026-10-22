package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.IdBatch;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Verifies the fixtures themselves, not any behaviour that handles money — so it is tagged {@code
 * integration} and kept out of the default {@code make verify} loop. It is also the most expensive
 * test in the build: it boots all six containers, and five of them (Postgres, Redis, RabbitMQ,
 * Redpanda, object store) are used by no other test in the repository yet. Run it via {@code make
 * verify-all}, and expect it to matter again as the phases that use those fixtures land.
 */
@Tag("integration")
class AllFixturesTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(AllFixturesTest.class);

  @Test
  void testAllSixFixturesOperationalWithin60Seconds() throws Exception {
    long startTime = System.currentTimeMillis();

    // 1. PostgresFixture
    PostgresFixture pg = PostgresFixture.getInstance();
    assertThat(pg.getJdbcUrl()).contains("jdbc:postgresql://");
    try (Connection conn = pg.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT 1");
    }
    pg.reset();

    // 2. RedisFixture
    RedisFixture redis = RedisFixture.getInstance();
    assertThat(redis.getRedisUrl()).startsWith("redis://");
    try (RedisClient client = RedisClient.create(redis.getRedisUrl());
        StatefulRedisConnection<String, String> conn = client.connect()) {
      conn.sync().set("all_fixtures_key", "test_val");
      assertThat(conn.sync().get("all_fixtures_key")).isEqualTo("test_val");
    }
    redis.reset();

    // 3. RabbitMqFixture
    RabbitMqFixture rabbit = RabbitMqFixture.getInstance();
    assertThat(rabbit.getAmqpUrl()).startsWith("amqp://");
    rabbit.reset();

    // 4. ObjectStoreFixture
    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    assertThat(objectStore.getS3Url()).startsWith("http://");
    try (S3Client s3 = objectStore.createS3Client()) {
      assertThat(s3.listBuckets().buckets()).isNotNull();
    }
    objectStore.reset();

    // 6. TigerBeetleFixture
    TigerBeetleFixture tb = TigerBeetleFixture.getInstance();
    assertThat(tb.getAddresses()).isNotNull();
    try (Client client = tb.getClient()) {
      IdBatch ids = new IdBatch(1);
      ids.add();
      ids.setId(1, 0);
      AccountBatch accounts = client.lookupAccounts(ids);
      assertThat(accounts).isNotNull();
    }
    tb.reset();

    long durationMs = System.currentTimeMillis() - startTime;
    LOGGER.info("Measured total execution time for all six fixtures: {} ms", durationMs);

    assertThat(durationMs)
        .as("Test using all six fixtures must complete under 60 seconds")
        .isLessThan(60_000L);
  }
}
