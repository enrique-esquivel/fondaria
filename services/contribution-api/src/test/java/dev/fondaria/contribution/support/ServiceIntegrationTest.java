package dev.fondaria.contribution.support;

import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RedisFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Points a Spring context at the shared Testcontainers fixtures, so an integration test configures
 * nothing itself and every one of them agrees on where PostgreSQL, TigerBeetle, Redis and the
 * object store are.
 *
 * <p><b>The derivation namespaces and the bucket name are random per JVM, and that is
 * deliberate.</b> Fixtures are reused across local runs and TigerBeetle cannot be reset at all
 * (AGENTS.md §4). A fixed namespace would mean the second run of a suite deriving the same account
 * ids as the first, so a test could pass on accounts a previous run opened — intermittently, and
 * about money. A fresh namespace gives every run its own id space, including for platform
 * singletons such as {@code BANK_CLEARING}, while a fresh tax identifier per test does the same for
 * the organizations themselves, and a fresh bucket does the same for uploaded files.
 *
 * <p>Redis is pointed at the shared fixture rather than left unconfigured, because absence of the
 * Redis variables is a startup failure by design (see {@code CacheProperties}) — a cache outage
 * must not fail a request, but a deployment that forgot to configure one must not start. A test
 * that needs Redis genuinely gone overrides these with a container of its own and stops it; it must
 * never stop this one, which every other test in the JVM shares.
 */
@Tag("integration")
@Import(ServiceIntegrationTest.InternalServiceCaller.class)
public abstract class ServiceIntegrationTest {

  protected static final UUID ORGANIZATION_NAMESPACE = UUID.randomUUID();
  protected static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  /** Task 9.5: a fresh master key per JVM, the same reason the two namespaces above are fresh. */
  private static final String PARTICIPANT_IDENTITY_MASTER_KEY_BASE64 = generateMasterKeyBase64();

  /**
   * A bucket per JVM, for the same reason the namespaces above are per JVM. The service never
   * creates a bucket — production provisions it, so that its credentials need no {@code
   * s3:CreateBucket} — which is why it is created here rather than on first use.
   */
  protected static final String CONTRIBUTION_FILE_BUCKET =
      "contribution-files-" + UUID.randomUUID();

  @BeforeAll
  static void createContributionBucket() {
    try (S3Client s3 = ObjectStoreFixture.getInstance().createS3Client()) {
      s3.createBucket(CreateBucketRequest.builder().bucket(CONTRIBUTION_FILE_BUCKET).build());
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

    RedisFixture redis = RedisFixture.getInstance();
    registry.add("fondaria.cache.url", () -> "redis://" + redis.getHost() + ":" + redis.getPort());
    registry.add("fondaria.cache.password", redis::getPassword);

    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    registry.add("fondaria.object-store.endpoint", objectStore::getS3Url);
    registry.add("fondaria.object-store.access-key", objectStore::getAccessKey);
    registry.add("fondaria.object-store.secret-key", objectStore::getSecretKey);
    registry.add("fondaria.object-store.bucket", () -> CONTRIBUTION_FILE_BUCKET);

    // An hour, so the scheduled sweep never fires during a test. Validation is
    // asynchronous in production and driven explicitly here, by calling the sweep:
    // racing a timer would make every assertion about a batch's status intermittent,
    // which is the worst failure mode available for a test about money.
    registry.add("fondaria.batch-validation.poll-interval", () -> "1h");

    // A sweep claims oldest-first, and fixtures are reused across runs (AGENTS.md §4), so
    // workflow.contribution_batch still holds whatever earlier runs left claimable. With
    // the production batch size a test's own batch could be crowded out of its own sweep —
    // intermittently, and about money. Claiming the leftovers too is harmless: every
    // assertion is over an id the test created.
    registry.add("fondaria.batch-validation.batch-size", () -> 1_000_000);

    registry.add("fondaria.identity.organization-namespace", () -> ORGANIZATION_NAMESPACE);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);

    registry.add(
        "fondaria.participant-identity.master-key-base64",
        () -> PARTICIPANT_IDENTITY_MASTER_KEY_BASE64);
  }

  private static String generateMasterKeyBase64() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  /**
   * Sends every {@code TestRestTemplate} request as an internal service, so a test about what the
   * service does is not stopped at the door by the identity filter (Task 9.3). Who may do what is
   * proven separately, in {@code ContributionApiAuthorizationTest} and {@code
   * ParticipantIdentityAuthorizationTest}; this only gives the end-to-end tests a caller.
   *
   * <p>An {@code Authorization} header the test sets itself wins, so a test can still exercise a
   * narrower caller. Public so that a test which cannot extend this class (it owns its own property
   * source) can {@code @Import} it too.
   */
  @TestConfiguration(proxyBeanMethods = false)
  public static class InternalServiceCaller {

    private static final String TOKEN =
        unsignedToken("{\"sub\":\"integration-test\",\"roles\":[\"internal-service\"]}");

    @Bean
    RestTemplateCustomizer internalServiceAuthorization() {
      return restTemplate ->
          restTemplate
              .getInterceptors()
              .add(
                  (request, body, execution) -> {
                    if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                      request.getHeaders().setBearerAuth(TOKEN);
                    }
                    return execution.execute(request, body);
                  });
    }

    private static String unsignedToken(String claimsJson) {
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
          + "."
          + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
          + ".";
    }
  }
}
