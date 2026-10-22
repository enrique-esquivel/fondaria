package dev.fondaria.valuationapi.support;

import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RedisFixture;
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
 * Points a Spring context at the shared Testcontainers fixtures, so an integration test configures
 * nothing itself.
 *
 * <p><b>Redis joined in Task 20.6</b>, where it is the NAV cache's warm-start store — it was
 * deliberately absent before, when this service held no cache. It stays optional in production: the
 * cache reads {@code reference.fund_nav} and Redis only spares a just-started instance its first
 * database read. TigerBeetle joined in Task 20.4 — the strike reads {@code UNITS_IN_ISSUE} through
 * a real {@code LedgerPort}, not ingestion.
 *
 * <p><b>The bucket and the ledger namespace are random per JVM.</b> Fixtures are reused across
 * local runs and TigerBeetle cannot be reset at all (AGENTS.md §4). A fixed namespace would mean a
 * second run derives the same account ids as the first, so a test could pass on an account a
 * previous run opened — intermittently, and about money. Every test also generates its own fund ids
 * and asserts only over those — never a count over {@code workflow.valuation} or {@code
 * reference.fund_nav} as a whole, which would pass or fail on what earlier runs left behind.
 */
@Tag("integration")
public abstract class ValuationIntegrationTest {

  protected static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  protected static final String VALUATION_FILE_BUCKET = "valuation-files-" + UUID.randomUUID();

  /**
   * The service never creates a bucket — production provisions it, so its credentials need no
   * {@code s3:CreateBucket} — which is why it is created here rather than on first use.
   */
  @BeforeAll
  static void createValuationBucket() {
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      s3.createBucket(CreateBucketRequest.builder().bucket(VALUATION_FILE_BUCKET).build());
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

    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    registry.add("fondaria.object-store.endpoint", objectStore::getS3Url);
    registry.add("fondaria.object-store.access-key", objectStore::getAccessKey);
    registry.add("fondaria.object-store.secret-key", objectStore::getSecretKey);
    registry.add("fondaria.object-store.bucket", () -> VALUATION_FILE_BUCKET);

    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);

    // An hour, so neither scheduled job fires during a test. Ingestion is asynchronous in
    // production and driven explicitly here, by calling the sweep: racing a timer would
    // make every assertion about a batch's status intermittent.
    registry.add("fondaria.valuation-ingestion.poll-interval", () -> "1h");
    registry.add("fondaria.valuation-ingestion.ageing-alert-interval", () -> "1h");

    // A sweep claims oldest-first, and fixtures are reused across runs, so
    // workflow.valuation_batch still holds whatever earlier runs left claimable. With the
    // production batch size a test's own batch could be crowded out of its own sweep —
    // intermittently. Claiming the leftovers too is harmless: every assertion is over an
    // id the test created.
    registry.add("fondaria.valuation-ingestion.batch-size", () -> 1_000_000);

    // UTC, so a test's fixed dates mean the same thing wherever the suite runs. The
    // production zone is America/Mexico_City; which zone is in force is not what these
    // tests are about, and BusinessCalendarTest already covers the zone and DST rules.
    registry.add("fondaria.calendar.zone", () -> "UTC");

    RedisFixture redis = RedisFixture.getInstance();
    // The fixture's URL embeds the password; this service takes the two separately so a
    // secret never has to sit in a URL that a log line could render.
    registry.add(
        "fondaria.nav-cache.url", () -> "redis://" + redis.getHost() + ":" + redis.getPort());
    registry.add("fondaria.nav-cache.password", redis::getPassword);
    // An hour, so the sweep never fires mid-test. Convergence is driven explicitly by
    // calling it: racing a timer would make every assertion here intermittent.
    registry.add("fondaria.nav-cache.refresh-interval", () -> "1h");
  }
}
