package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.ContributionApiApplication;
import dev.fondaria.contribution.config.CacheProperties;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.testing.ContainerImages;
import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The headline acceptance criterion of Task 3.3: <b>with Redis stopped, resubmitting a receipt
 * still results in exactly one movement.</b>
 *
 * <p><b>Redis is genuinely stopped, not stubbed out.</b> A test double for the cache would prove
 * only that the double returns nothing; this kills a real Redis mid-test, so what is exercised is
 * Lettuce failing against a closed port, the {@code REJECT_COMMANDS} option, and the catch in
 * {@link RedisCashReceiptResponseCache} — the parts that actually decide whether a Redis outage
 * reaches a caller.
 *
 * <p>The container is this class's own, and is deliberately <b>not</b> {@code RedisFixture}: that
 * one is a reusable singleton shared by every other integration test in the JVM, and stopping it
 * would break them.
 *
 * <p>The first submission runs with Redis alive, so the cache is warm and holds the original
 * response. Killing it afterwards is the interesting order: it proves the retry does not depend on
 * a cache entry that <i>did</i> exist a moment ago and then vanished — which is exactly what a
 * Redis failover does, and exactly the failure mode 01-DECISIONS-TO-DISCUSS.md C3 rejected the
 * original {@code SETNX} design over.
 *
 * <p>The tests are ordered because the container is stopped and never restarted: the first one has
 * to be the one that needs a live Redis to begin with.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(ServiceIntegrationTest.InternalServiceCaller.class)
class CashReceiptWithoutRedisIntegrationTest {

  private static final long AMOUNT = 175_000L;
  private static final UUID ORGANIZATION_NAMESPACE = UUID.randomUUID();
  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final String PARTICIPANT_IDENTITY_MASTER_KEY = freshMasterKeyBase64();

  private static final GenericContainer<?> DISPOSABLE_REDIS =
      new GenericContainer<>(ContainerImages.REDIS)
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "funds_dev_pass")
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

  /**
   * Captured at start-up, because {@code getMappedPort} throws once the container is stopped — and
   * this class stops it on purpose.
   */
  private static final int DISPOSABLE_REDIS_PORT;

  static {
    DISPOSABLE_REDIS.start();
    DISPOSABLE_REDIS_PORT = DISPOSABLE_REDIS.getMappedPort(6379);
  }

  /**
   * This class configures the whole context itself rather than extending {@code
   * ServiceIntegrationTest}, and that is not an oversight. A subclass's
   * {@code @DynamicPropertySource} does <b>not</b> reliably override an inherited one — measured,
   * not assumed: the first version of this class extended the shared base, and the guard below
   * caught it still pointed at the shared {@code RedisFixture}, which would have made every
   * assertion here pass against a Redis that was never stopped.
   */
  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);

    // This class's own Redis, which it is going to stop. Never RedisFixture: that one is
    // a reusable singleton shared by every other integration test in the JVM.
    registry.add(
        "fondaria.cache.url",
        () -> "redis://" + DISPOSABLE_REDIS.getHost() + ":" + DISPOSABLE_REDIS_PORT);
    registry.add("fondaria.cache.password", () -> "funds_dev_pass");

    // Required since Task 3.4. Pointed at the shared object-store fixture rather than a
    // disposable one: this class stops Redis, not the object store, and nothing here
    // uploads or fetches an object — the S3 client is wired because absence of these
    // values is a startup failure by design.
    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    registry.add("fondaria.object-store.endpoint", objectStore::getS3Url);
    registry.add("fondaria.object-store.access-key", objectStore::getAccessKey);
    registry.add("fondaria.object-store.secret-key", objectStore::getSecretKey);
    registry.add("fondaria.object-store.bucket", () -> "cash-receipt-test-unused");

    // Fresh per JVM, for the reason ServiceIntegrationTest gives: fixtures are reused
    // between runs and TigerBeetle cannot be reset, so every run needs its own id space.
    registry.add("fondaria.identity.organization-namespace", () -> ORGANIZATION_NAMESPACE);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);

    // Required since Task 9.5, and fresh per JVM for the same reason as the namespaces.
    registry.add(
        "fondaria.participant-identity.master-key-base64", () -> PARTICIPANT_IDENTITY_MASTER_KEY);

    // An hour, as in ServiceIntegrationTest. This context stays cached for the rest of the
    // JVM, and at the default interval its sweep claims every other test's batches from the
    // shared database and, looking for their files in the unused bucket above, rejects them.
    registry.add("fondaria.batch-validation.poll-interval", () -> "1h");
  }

  /**
   * Proves this context is wired to the disposable Redis before any test stops it.
   *
   * <p>Without this check the whole class could pass while proving nothing: if the inherited
   * property source won and the application were still pointed at the shared {@code RedisFixture},
   * stopping the disposable container would leave a perfectly healthy cache in place and every
   * assertion below would hold for the wrong reason. A silent false pass on the one test that
   * guards against double-crediting is worse than no test.
   */
  @BeforeEach
  void pointedAtTheDisposableRedis() {
    assertThat(cacheProperties.url())
        .as("this class must own the Redis it is about to stop")
        .endsWith(":" + DISPOSABLE_REDIS_PORT);
  }

  @Autowired private CacheProperties cacheProperties;
  @Autowired private TestRestTemplate http;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private CashReceiptRepository receipts;

  @Test
  @Order(1)
  @DisplayName("Redis stopped: a duplicate submission still results in exactly one movement")
  void redisStoppedStillMovesMoneyOnce() {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();

    ResponseEntity<Map<String, Object>> first = post(organizationId, key, AMOUNT);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));

    DISPOSABLE_REDIS.stop();

    ResponseEntity<Map<String, Object>> second = post(organizationId, key, AMOUNT);

    assertThat(second.getStatusCode())
        .as("a cold cache is a slower answer, never a different one")
        .isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(collectionsBalance(organizationId))
        .as("the ledger's derived transfer id refused the duplicate, with no cache involved")
        .isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
    assertThat(receipts.find(organizationId, key))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.POSTED);
  }

  @Test
  @Order(2)
  @DisplayName("Redis stopped: the same key with a different amount is still a 422")
  void redisStoppedStillDetectsKeyReuse() {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();
    post(organizationId, key, AMOUNT);

    DISPOSABLE_REDIS.stop();

    ResponseEntity<Map<String, Object>> reused = post(organizationId, key, AMOUNT + 1);

    assertThat(reused.getStatusCode())
        .as("the fingerprint lives on the PostgreSQL row, so the check survives a cold cache")
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @Order(3)
  @DisplayName("Redis stopped: a first-time receipt is still accepted")
  void redisStoppedStillAcceptsNewReceipts() {
    OrganizationId organizationId = registerOrganization();

    DISPOSABLE_REDIS.stop();

    ResponseEntity<Map<String, Object>> response =
        post(organizationId, "key-" + UUID.randomUUID(), AMOUNT);

    assertThat(response.getStatusCode())
        .as("a cache outage must not stop money being recorded")
        .isEqualTo(HttpStatus.CREATED);
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @Order(4)
  @DisplayName("Redis stopped: the service still starts")
  void redisStoppedStillStarts() {
    // The context above was built while Redis was alive. This one is not — and a service
    // that cannot start without its cache would mean a pod restarting during a Redis
    // outage crash-loops, leaving no cash receipt postable at all. See CacheConfiguration:
    // the connection is lazy precisely so this holds.
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ContributionApiApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--fondaria.database.url=" + PostgresFixture.getInstance().getJdbcUrl(),
                "--fondaria.database.username=" + PostgresFixture.getInstance().getUsername(),
                "--fondaria.database.password=" + PostgresFixture.getInstance().getPassword(),
                "--fondaria.ledger.addresses=" + TigerBeetleFixture.getInstance().getAddresses(),
                "--fondaria.ledger.client.cluster-id=0",
                "--fondaria.cache.url=redis://"
                    + DISPOSABLE_REDIS.getHost()
                    + ":"
                    + DISPOSABLE_REDIS_PORT,
                "--fondaria.cache.password=funds_dev_pass",
                // Required since Task 3.4. Wired but never used: this test stops Redis,
                // not the object store, and starting up is all it asserts.
                "--fondaria.object-store.endpoint=" + ObjectStoreFixture.getInstance().getS3Url(),
                "--fondaria.object-store.access-key="
                    + ObjectStoreFixture.getInstance().getAccessKey(),
                "--fondaria.object-store.secret-key="
                    + ObjectStoreFixture.getInstance().getSecretKey(),
                "--fondaria.object-store.bucket=cash-receipt-test-unused",
                "--fondaria.identity.organization-namespace=" + ORGANIZATION_NAMESPACE,
                "--fondaria.identity.ledger-namespace=" + LEDGER_NAMESPACE,
                "--fondaria.participant-identity.master-key-base64="
                    + PARTICIPANT_IDENTITY_MASTER_KEY,
                "--fondaria.batch-validation.poll-interval=1h")) {
      assertThat(context.isRunning()).isTrue();
      assertThat(context.getBean(CashReceiptService.class)).isNotNull();
    }
  }

  private ResponseEntity<Map<String, Object>> post(
      OrganizationId organizationId, String idempotencyKey, long cents) {
    return http.exchange(
        "/v1/organizations/" + organizationId + "/cash-receipts",
        HttpMethod.POST,
        json(
            "{\"amountMinorUnits\":"
                + cents
                + ",\"currency\":\"MXN\",\"valueDate\":\"2026-08-18\","
                + "\"bankReference\":\"SPEI-2026-08-18-0007\",\"operatorId\":\"ops-marta\"}",
            idempotencyKey),
        new ParameterizedTypeReference<>() {});
  }

  private Money collectionsBalance(OrganizationId organizationId) {
    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    return ((LedgerAccount.Cash) ledger.lookupAccount(collections).orElseThrow()).balance();
  }

  private OrganizationId registerOrganization() {
    ResponseEntity<Map<String, Object>> response =
        http.exchange(
            "/v1/organizations",
            HttpMethod.POST,
            json(
                "{\"taxIdScheme\":\"MX_RFC\",\"taxId\":\""
                    + FreshTaxIdentifiers.freshRfc()
                    + "\",\"legalName\":\"Acme S.A. de C.V.\"}",
                null),
            new ParameterizedTypeReference<>() {});
    return OrganizationId.parse(String.valueOf(response.getBody().get("organizationId")));
  }

  private static HttpEntity<String> json(String body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return new HttpEntity<>(body, headers);
  }

  private static String freshMasterKeyBase64() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
