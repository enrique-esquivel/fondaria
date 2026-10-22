package dev.fondaria.eodjob.support;

import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import dev.fondaria.testing.TigerBeetleFixture;
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
 * Points a Spring context at the shared Testcontainers fixtures, mirroring contribution-api's,
 * valuation-api's and allotment-job's classes of the same name.
 *
 * <p>{@code LEDGER_NAMESPACE} is random per JVM, for the reason every one of those states: fixtures
 * are reused across local runs and TigerBeetle cannot be reset (docs/ENGINEERING-RULES.md §4). It
 * matters particularly here — a shared namespace would let this suite read holding accounts an
 * earlier run opened and report their units as this run's drift.
 *
 * <p><b>The poll intervals are an hour, and that is not the same as "nothing fires".</b> A {@code
 * fixedDelay} schedule with no initial delay runs <i>once immediately</i> when the context starts;
 * the interval only governs what comes after. So every context here runs one real sweep of each
 * pass over the shared database before any test method executes, and the interval is what stops a
 * second.
 *
 * <p>That is harmless rather than accidental, but only because of a property this class has to
 * keep: every runner targets <i>today's</i> business date, and every test in this service works on
 * a business date far outside it. Today has no passing internal or custodian reconciliation, so all
 * four gates fail closed — the snapshot sweep and the sign-off sweep (Task 7.5a) each return having
 * read and written nothing, and no {@code close.signed-off.v1} is published. A test that recorded a
 * passing reconciliation for the <i>current</i> date would break that, and the start-up sweeps
 * would begin writing into the shared fixture and onto the shared broker.
 *
 * <p>Every test drives its own pass explicitly, which is the only way to assert on what one pass
 * found.
 *
 * <p>The object store joins the fixtures with Task 7.4 — the dense snapshot writes its file and
 * manifest there, and {@code ObjectStoreProperties} fails startup when a bucket is absent by
 * design, so every context in this service needs one configured whether or not the test under it
 * writes an object. Redpanda joins with Task 7.5a for the same reason: {@code SignoffConfiguration}
 * fails startup without a bootstrap server.
 */
@Tag("integration")
public abstract class ServiceIntegrationTest {

  protected static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  /**
   * A bucket per JVM, for the same reason the namespace above is per JVM: fixtures are reused
   * across runs, and a fixed name would let one run's assertions see another's objects. The service
   * never creates a bucket — production provisions it, so that its credentials need no {@code
   * s3:CreateBucket} — which is why it is created here rather than on first use.
   */
  protected static final String SNAPSHOT_BUCKET = "eod-snapshots-" + UUID.randomUUID();

  @BeforeAll
  static void createSnapshotBucket() {
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      s3.createBucket(CreateBucketRequest.builder().bucket(SNAPSHOT_BUCKET).build());
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
      // Another test class in this JVM got there first. The bucket is per JVM rather than
      // per class, so this is the ordinary case and not a problem.
    }
  }

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);

    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    registry.add("fondaria.snapshot-object-store.endpoint", objectStore::getS3Url);
    registry.add("fondaria.snapshot-object-store.access-key", objectStore::getAccessKey);
    registry.add("fondaria.snapshot-object-store.secret-key", objectStore::getSecretKey);
    registry.add("fondaria.snapshot-object-store.bucket", () -> SNAPSHOT_BUCKET);

    RabbitMqFixture rabbit = RabbitMqFixture.getInstance();
    registry.add("spring.rabbitmq.host", rabbit::getHost);
    registry.add("spring.rabbitmq.port", rabbit::getPort);
    registry.add("spring.rabbitmq.username", rabbit::getUsername);
    registry.add("spring.rabbitmq.password", rabbit::getPassword);
    registry.add("fondaria.signoff.amqp-host", rabbit::getHost);

    registry.add("fondaria.reconciliation.poll-interval", () -> "1h");
    registry.add("fondaria.custodian-reconciliation.poll-interval", () -> "1h");
    registry.add("fondaria.snapshot.poll-interval", () -> "1h");
    registry.add("fondaria.signoff.poll-interval", () -> "1h");
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);
  }
}
