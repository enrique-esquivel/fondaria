package dev.fondaria.statementworker.support;

import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import dev.fondaria.testing.RedisFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Points a Spring context at the shared Testcontainers fixtures, mirroring {@code eod-job}'s class
 * of the same name.
 *
 * <p>No TigerBeetle: this service holds no {@link dev.fondaria.ledger.LedgerPort} at all (see
 * {@code StatementWorkerApplication}'s own javadoc). Redis joins the fixtures for the completion
 * counter ({@code ADR-0022} §3); RabbitMQ for both {@code close.signed-off.v1} and {@code
 * statement.jobs.v1}; The object store for both the snapshot bucket (read-only) and the statement
 * bucket (write-only) — two buckets, one fixture.
 *
 * <p>Buckets are per-JVM-random, and the settlement sweep runs on a one-second interval rather than
 * the production default, so a test does not wait multiple seconds for a run to settle. Every test
 * still asserts only over the period and participants it created itself (docs/ENGINEERING-RULES.md
 * §4): fixtures are reused across local runs.
 */
@Tag("integration")
public abstract class ServiceIntegrationTest {

  protected static final String SNAPSHOT_BUCKET = "statement-it-snapshots-" + UUID.randomUUID();
  protected static final String STATEMENT_BUCKET = "statement-it-statements-" + UUID.randomUUID();

  @BeforeAll
  static void createBuckets() {
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      createBucket(s3, SNAPSHOT_BUCKET);
      createBucket(s3, STATEMENT_BUCKET);
    }
  }

  private static void createBucket(S3Client s3, String bucket) {
    try {
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
      // Another test class in this JVM got there first — the bucket is per JVM, not per class.
    }
  }

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    registry.add("fondaria.statement-object-store.endpoint", objectStore::getS3Url);
    registry.add("fondaria.statement-object-store.access-key", objectStore::getAccessKey);
    registry.add("fondaria.statement-object-store.secret-key", objectStore::getSecretKey);
    registry.add("fondaria.statement-object-store.snapshot-bucket", () -> SNAPSHOT_BUCKET);
    registry.add("fondaria.statement-object-store.statement-bucket", () -> STATEMENT_BUCKET);

    RabbitMqFixture rabbitmq = RabbitMqFixture.getInstance();
    registry.add("spring.rabbitmq.host", rabbitmq::getHost);
    registry.add("spring.rabbitmq.port", rabbitmq::getPort);
    registry.add("spring.rabbitmq.username", rabbitmq::getUsername);
    registry.add("spring.rabbitmq.password", rabbitmq::getPassword);
    registry.add("fondaria.statement.amqp-host", rabbitmq::getHost);

    RedisFixture redis = RedisFixture.getInstance();
    registry.add(
        "fondaria.statement-redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getPort());
    registry.add("fondaria.statement-redis.password", redis::getPassword);

    registry.add("fondaria.statement.settlement-sweep-interval", () -> "1s");
  }
}
