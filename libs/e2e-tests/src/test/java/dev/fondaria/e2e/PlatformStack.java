package dev.fondaria.e2e;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import dev.fondaria.allotmentjob.AllotmentJobApplication;
import dev.fondaria.balanceapi.BalanceApiApplication;
import dev.fondaria.bridge.BridgeApplication;
import dev.fondaria.contribution.ContributionApiApplication;
import dev.fondaria.eodjob.EodJobApplication;
import dev.fondaria.projection.ProjectionWorkerApplication;
import dev.fondaria.testing.ContainerImages;
import dev.fondaria.testing.ObjectStoreFixture;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import dev.fondaria.testing.RedisFixture;
import dev.fondaria.testing.TempoFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import dev.fondaria.valuationapi.ValuationApiApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * The platform, running: every service on the path from a contribution file to a query, the service
 * that prices the fund those contributions buy into, the real CDC job, and the six fixtures behind
 * them. Started once per JVM and shared by every test class in this module — the stack costs a
 * minute to raise and nothing a test does to it needs it torn down, because every test derives its
 * own organization, fund and participant (AGENTS.md §4).
 *
 * <h2>What is real here, and what is not</h2>
 *
 * <ul>
 *   <li><b>Real:</b> {@code contribution-api} (upload, cash receipt, validation, staging,
 *       enrolment), {@code allotment-job} (the {@code CONTRIBUTION_ALLOTMENT} chain), TigerBeetle,
 *       the stock {@code tigerbeetle amqp} CDC job running {@code
 *       infrastructure/compose/cdc/run.sh} unchanged, RabbitMQ, {@code amqp-redpanda-bridge},
 *       Redpanda, {@code projection-worker}, PostgreSQL and {@code balance-api} answering over
 *       HTTP.
 *   <li><b>Real since Task 20.7:</b> {@code valuation-api} — a provider's file in object storage,
 *       its validation sweep, the strike against units in issue read from TigerBeetle, and
 *       publication into the NAV cache of both services that hold one. ADR-0016 recorded the NAV
 *       strike as the single hop these tests faked; {@link NavPublicationEndToEndTest} closes it.
 *   <li><b>Still written directly:</b> the {@code reference.fund_nav} row {@link
 *       Scenario#strikeAndPublish} inserts. Task 6.6's tests need a price to <i>exist</i>, not a
 *       price to have been struck, and one of them needs a price dated <i>after</i> the dealing
 *       date — which no valuation file may carry, because a fund cannot be valued on a date that
 *       has not happened.
 * </ul>
 *
 * <h2>Why every service here shares one identity namespace</h2>
 *
 * <p>{@code LEDGER_ID_NAMESPACE} is the seed for every account and transfer id on the platform.
 * {@code allotment-job} opens a participant's holding account under it and {@code balance-api}
 * re-derives that same id to read the balance; a stack whose services disagreed about it would
 * report every participant as holding nothing, and no assertion would say why. {@code
 * valuation-api} joins that agreement rather than standing outside it: the strike divides by {@code
 * UNITS_IN_ISSUE[fund]}, an account the allotment opened under this same namespace, so a
 * disagreement there would price every fund at its opening price forever. So the contexts are given
 * one namespace, freshly generated per JVM — fresh because TigerBeetle cannot be reset and a fixed
 * namespace would let a run pass on accounts an earlier run opened.
 *
 * <h2>Why the CDC job is started at "now"</h2>
 *
 * <p>{@code CDC_TIMESTAMP_LAST} is set to the wall clock at startup. The shared TigerBeetle holds
 * everything every suite has ever posted to it, and a connector resuming from the beginning would
 * replay all of it through the bridge and into the projection before this module's own first
 * transfer appeared. Starting at "now" bounds the stream to what these tests themselves post; it
 * changes nothing about the path a movement travels.
 */
final class PlatformStack {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlatformStack.class);

  /**
   * The Task 4.1 topology objects. Names, not tuning — the CDC job and the bridge both fix them.
   */
  private static final String EXCHANGE = "tigerbeetle";

  private static final String BRIDGE_QUEUE = "bridge.ledger";
  private static final String BRIDGE_DLQ = "bridge.ledger.dlq";

  /** projection-worker's queues (ADR-0034): one transfers queue, sixteen consistent-hash ones. */
  private static final String PROJECTION_TRANSFERS_QUEUE = "projection.transfers.v1";

  private static final String PROJECTION_ENTRIES_QUEUE_PREFIX = "projection.entries.v1.";

  private static final int PROJECTION_ENTRIES_QUEUE_COUNT = 16;

  /**
   * The topics the bridge produces to (Task 4.3, {@code LedgerTopics}). Constants, not settings.
   */
  private static PlatformStack instance;

  private static RuntimeException startFailure;

  /** One namespace for the whole stack. See the class javadoc. */
  final UUID ledgerNamespace = UUID.randomUUID();

  final UUID organizationNamespace = UUID.randomUUID();

  final String contributionBucket = "contribution-files-" + UUID.randomUUID();

  /** contribution-api's participant-identity master key (Task 9.5), fresh per JVM. */
  private final String participantIdentityMasterKey = freshMasterKeyBase64();

  /**
   * A separate bucket from the contribution one, as in production: different producers, different
   * retention, different access. Per JVM, because fixtures are reused across runs.
   */
  final String valuationBucket = "valuation-files-" + UUID.randomUUID();

  /** Where eod-job's Task 7.4 snapshot writes. A third bucket, same reasoning as the other two. */
  final String snapshotBucket = "snapshots-" + UUID.randomUUID();

  private ConfigurableApplicationContext contributionApi;
  private ConfigurableApplicationContext allotmentJob;
  private ConfigurableApplicationContext bridge;
  private ConfigurableApplicationContext projectionWorker;
  private ConfigurableApplicationContext balanceApi;
  private ConfigurableApplicationContext valuationApi;
  private ConfigurableApplicationContext eodJob;
  private String balanceApiBaseUrl;
  private String contributionApiBaseUrl;

  private PlatformStack() {}

  static synchronized PlatformStack getInstance() {
    if (startFailure != null) {
      // A half-raised stack is worse than none: the second class would tear down objects the
      // first one's CDC job still holds and report that as the failure, burying the real one.
      throw new IllegalStateException("the platform stack failed to start", startFailure);
    }
    if (instance == null) {
      PlatformStack stack = new PlatformStack();
      try {
        stack.start();
      } catch (RuntimeException e) {
        startFailure = e;
        throw e;
      }
      instance = stack;
    }
    return instance;
  }

  /**
   * Messages ready in each queue a ledger event passes through on its way to the projection, in
   * pipeline order: the bridge's queue, then projection-worker's transfers and entries queues. An
   * unacknowledged delivery (a batch being written) is not counted; {@link #awaitPipelineDrained}
   * allows for that by requiring the queues to stay empty for a moment.
   */
  Map<String, Long> pipelineQueueDepths() {
    RabbitMqFixture rabbit = RabbitMqFixture.getInstance();
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getAmqpPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    factory.setConnectionTimeout(5_000);
    Map<String, Long> depths = new LinkedHashMap<>();
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      for (String queue : pipelineQueues()) {
        depths.put(queue, (long) channel.queueDeclarePassive(queue).getMessageCount());
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read the pipeline's queue depths", e);
    }
    return depths;
  }

  /**
   * Waits until every pipeline queue is empty and stays empty for two seconds. A test that floods
   * the ledger calls this before it returns: the stack is shared, and a backlog left behind makes
   * the next class's convergence wait time out on someone else's events.
   */
  void awaitPipelineDrained(Duration budget) {
    org.awaitility.Awaitility.await()
        .alias("the projection pipeline drains")
        .atMost(budget)
        .pollInterval(Duration.ofSeconds(1))
        .during(Duration.ofSeconds(2))
        .until(() -> pipelineQueueDepths().values().stream().mapToLong(Long::longValue).sum() == 0);
  }

  private static List<String> pipelineQueues() {
    List<String> queues = new ArrayList<>();
    queues.add(BRIDGE_QUEUE);
    queues.add(PROJECTION_TRANSFERS_QUEUE);
    for (int i = 0; i < PROJECTION_ENTRIES_QUEUE_COUNT; i++) {
      queues.add(PROJECTION_ENTRIES_QUEUE_PREFIX + i);
    }
    return queues;
  }

  <T> T contributionApiBean(Class<T> type) {
    return contributionApi.getBean(type);
  }

  <T> T allotmentJobBean(Class<T> type) {
    return allotmentJob.getBean(type);
  }

  <T> T balanceApiBean(Class<T> type) {
    return balanceApi.getBean(type);
  }

  <T> T valuationApiBean(Class<T> type) {
    return valuationApi.getBean(type);
  }

  <T> T eodJobBean(Class<T> type) {
    return eodJob.getBean(type);
  }

  /** Where {@code balance-api} is listening, e.g. {@code http://localhost:51234}. */
  String balanceApiBaseUrl() {
    return balanceApiBaseUrl;
  }

  /**
   * An {@code Authorization} header value for an internal service caller. contribution-api and
   * balance-api answer 401 to a request without one (Task 9.3); who may do what is proven by their
   * own authorization tests, so this module calls as the most trusted population and asserts
   * behaviour only.
   */
  static String internalServiceAuthorization() {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String claims = "{\"sub\":\"e2e-tests\",\"roles\":[\"internal-service\"]}";
    return "Bearer "
        + encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
        + ".";
  }

  private static String freshMasterKeyBase64() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  /** Where {@code contribution-api} is listening, e.g. {@code http://localhost:51234}. */
  String contributionApiBaseUrl() {
    return contributionApiBaseUrl;
  }

  private void start() {
    long startedAt = System.nanoTime();

    PostgresFixture postgres = PostgresFixture.getInstance();
    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    RabbitMqFixture rabbit = RabbitMqFixture.getInstance();
    RedisFixture redis = RedisFixture.getInstance();
    ObjectStoreFixture objectStore = ObjectStoreFixture.getInstance();
    // Task 11.2. Every service points its OTLP exporter directly at this container (see
    // TempoFixture's own javadoc for why a test may do this and production may not).
    TempoFixture tempo = TempoFixture.getInstance();
    createBucket(objectStore, contributionBucket);
    createBucket(objectStore, valuationBucket);
    createBucket(objectStore, snapshotBucket);

    declareAmqpTopology(rabbit);
    startCdcJob(tigerBeetle, rabbit);

    Map<String, Object> shared = new HashMap<>();
    shared.put("POSTGRES_URL", postgres.getJdbcUrl());
    shared.put("POSTGRES_USER", postgres.getUsername());
    shared.put("POSTGRES_PASSWORD", postgres.getPassword());
    shared.put("TIGERBEETLE_ADDRESSES", tigerBeetle.getAddresses());
    shared.put("TIGERBEETLE_CLUSTER_ID", "0");
    shared.put("LEDGER_ID_NAMESPACE", ledgerNamespace.toString());
    shared.put("ORGANIZATION_ID_NAMESPACE", organizationNamespace.toString());
    shared.put("server.port", "0");
    shared.put("spring.main.banner-mode", "off");
    // Task 11.2: every service's spans and metrics go straight to Tempo (see TempoFixture).
    shared.put("OTEL_EXPORTER_OTLP_ENDPOINT", tempo.getOtlpHttpEndpoint());

    contributionApi =
        run(
            ContributionApiApplication.class,
            shared,
            Map.of(
                "REDIS_URL",
                "redis://" + redis.getHost() + ":" + redis.getPort(),
                "REDIS_PASSWORD",
                redis.getPassword(),
                "OBJECT_STORE_ENDPOINT",
                objectStore.getS3Url(),
                "OBJECT_STORE_ACCESS_KEY",
                objectStore.getAccessKey(),
                "OBJECT_STORE_SECRET_KEY",
                objectStore.getSecretKey(),
                "CONTRIBUTION_FILE_BUCKET",
                contributionBucket,
                // Both sweeps are driven explicitly by the tests. A timer firing mid-test would
                // make every assertion about a batch's status intermittent, which is the worst
                // failure mode available for a test about money (AGENTS.md §4).
                "BATCH_VALIDATION_POLL_INTERVAL",
                "1h",
                // Fixtures are reused across runs, so the workflow tables still hold whatever
                // earlier runs left claimable; a production-sized sweep could crowd a test's own
                // batch out of its own sweep. Claiming the leftovers is harmless — every
                // assertion in this module is over an id it created itself.
                "BATCH_VALIDATION_BATCH_SIZE",
                "1000000",
                "PARTICIPANT_IDENTITY_MASTER_KEY",
                participantIdentityMasterKey));
    contributionApiBaseUrl =
        "http://localhost:"
            + ((ServletWebServerApplicationContext) contributionApi).getWebServer().getPort();

    allotmentJob =
        run(
            AllotmentJobApplication.class,
            shared,
            Map.of(
                "ALLOTMENT_POLL_INTERVAL",
                "1h",
                "ALLOTMENT_BATCH_SIZE",
                "1000000",
                "fondaria.vesting.poll-interval",
                "1h"));

    projectionWorker =
        run(
            ProjectionWorkerApplication.class,
            shared,
            Map.of(
                "PROJECTION_AMQP_HOST",
                rabbit.getHost(),
                "PROJECTION_AMQP_PORT",
                String.valueOf(rabbit.getAmqpPort()),
                "PROJECTION_AMQP_USERNAME",
                rabbit.getUsername(),
                "PROJECTION_AMQP_PASSWORD",
                rabbit.getPassword(),
                "PROJECTION_AMQP_VHOST",
                "/",
                // A halt is a row in the reused database; a fresh name means one this run raises
                // cannot stop a later run's projection worker (ADR-0032 §2).
                "fondaria.projection.halt.projection-name",
                "e2e-" + java.util.UUID.randomUUID(),
                // Task 11.2: the same physical Redis contribution-api's REDIS_URL points at,
                // so a trace correlation entry written there is found here.
                "TRACE_CORRELATION_REDIS_URL",
                "redis://" + redis.getHost() + ":" + redis.getPort(),
                "TRACE_CORRELATION_REDIS_PASSWORD",
                redis.getPassword()));

    bridge =
        run(
            BridgeApplication.class,
            shared,
            Map.of(
                "BRIDGE_AMQP_HOST", rabbit.getHost(),
                "BRIDGE_AMQP_PORT", String.valueOf(rabbit.getAmqpPort()),
                "BRIDGE_AMQP_USERNAME", rabbit.getUsername(),
                "BRIDGE_AMQP_PASSWORD", rabbit.getPassword(),
                "BRIDGE_AMQP_VHOST", "/",
                // The bridge holds no database, on purpose (NoDatabaseDependencyTest), and its
                // own classpath has no JDBC on it. This one does, because six services share
                // it, so Boot's DataSourceAutoConfiguration applies where in production it
                // cannot and fails the context on a driver it should never have looked for.
                // Excluding it restores the bridge's real shape rather than papering over it:
                // a database appearing in this list later would mean the bridge grew a
                // dependency it must not have.
                "spring.autoconfigure.exclude",
                    String.join(
                        ",",
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "org.springframework.boot.autoconfigure.jdbc"
                            + ".DataSourceTransactionManagerAutoConfiguration",
                        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")));

    balanceApi =
        run(
            BalanceApiApplication.class,
            shared,
            Map.of(
                // The NAV cache is refreshed explicitly by the tests, for the same reason the
                // sweeps above are: a background refresh landing mid-assertion would make a
                // figure about money depend on a timer.
                "NAV_CACHE_REFRESH_INTERVAL",
                "1h",
                "QUERY_CURSOR_SECRET",
                "e2e-cursor-signing-key-" + UUID.randomUUID()));
    balanceApiBaseUrl =
        "http://localhost:"
            + ((ServletWebServerApplicationContext) balanceApi).getWebServer().getPort();

    valuationApi =
        run(
            ValuationApiApplication.class,
            shared,
            Map.of(
                "OBJECT_STORE_ENDPOINT",
                objectStore.getS3Url(),
                "OBJECT_STORE_ACCESS_KEY",
                objectStore.getAccessKey(),
                "OBJECT_STORE_SECRET_KEY",
                objectStore.getSecretKey(),
                "VALUATION_FILE_BUCKET",
                valuationBucket,
                // The fixture's URL embeds the password; this service takes the two
                // separately so a secret never sits in a URL a log line could render.
                "NAV_CACHE_REDIS_URL",
                "redis://" + redis.getHost() + ":" + redis.getPort(),
                "NAV_CACHE_REDIS_PASSWORD",
                redis.getPassword(),
                // Every sweep in this service is driven explicitly by the tests, for the
                // reason the two above are: a timer firing mid-test would make an assertion
                // about a price depend on when the scheduler happened to run.
                "VALUATION_POLL_INTERVAL",
                "1h",
                "VALUATION_AGEING_ALERT_INTERVAL",
                "1h",
                "NAV_CACHE_REFRESH_INTERVAL",
                "1h",
                // Fixtures are reused across runs, so workflow.valuation_batch still holds
                // whatever earlier runs left claimable, and a sweep claims oldest-first. With
                // the production batch size a test's own batch could be crowded out of its own
                // sweep. Claiming the leftovers is harmless: every assertion here is over an
                // id the test created itself.
                "VALUATION_BATCH_SIZE",
                "1000000"));

    eodJob =
        run(
            EodJobApplication.class,
            shared,
            Map.of(
                // The close is driven explicitly by the tests, for the reason every other sweep
                // here is: a pass firing on a timer mid-test would tie a book that was still
                // moving and report the movement as drift.
                "RECONCILIATION_POLL_INTERVAL", "1h",
                // Pre-existing gaps found while verifying Task 11.2, not owned by it: Tasks
                // 7.4 and 7.5a added required variables (the snapshot's object store, the
                // signoff producer's broker) that this map was never updated for, so eod-job
                // — and therefore every test in this module, since PlatformStack starts all
                // seven contexts — has failed to start since. Same fixtures every other
                // service here already uses.
                "SIGNOFF_AMQP_HOST", rabbit.getHost(),
                "SIGNOFF_AMQP_PORT", String.valueOf(rabbit.getAmqpPort()),
                "SIGNOFF_AMQP_USERNAME", rabbit.getUsername(),
                "SIGNOFF_AMQP_PASSWORD", rabbit.getPassword(),
                "SIGNOFF_AMQP_VHOST", "/",
                "OBJECT_STORE_ENDPOINT", objectStore.getS3Url(),
                "OBJECT_STORE_ACCESS_KEY", objectStore.getAccessKey(),
                "OBJECT_STORE_SECRET_KEY", objectStore.getSecretKey(),
                "SNAPSHOT_BUCKET", snapshotBucket));

    LOGGER.info(
        "platform stack up in {} ms; balance-api at {}",
        (System.nanoTime() - startedAt) / 1_000_000,
        balanceApiBaseUrl);
  }

  /**
   * Starts one service, reading <b>its own</b> {@code application.yaml}.
   *
   * <p>Seven Spring Boot applications share this JVM's classpath, and every one of them carries an
   * {@code application.yaml} at the classpath root. {@code classpath:/application.yaml} resolves to
   * exactly one resource, so without this every service would be configured from whichever jar
   * happened to be first — which is not a subtle failure: the projection worker started against
   * contribution-api's file and refused to boot, naming a broker variable that had been set all
   * along. Each context is therefore pointed at the file inside its own artifact, located from the
   * application class rather than from a classpath string (surefire hands the JVM a manifest-only
   * jar, so {@code java.class.path} names nothing useful).
   */
  private static ConfigurableApplicationContext run(
      Class<?> application, Map<String, Object> shared, Map<String, String> own) {
    Map<String, Object> properties = new HashMap<>(shared);
    properties.putAll(own);
    properties.put("spring.config.location", ownConfigurationFile(application));
    return new SpringApplicationBuilder(application).properties(properties).run();
  }

  private static String ownConfigurationFile(Class<?> application) {
    try {
      Path artifact =
          Paths.get(application.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isDirectory(artifact)) {
        return "file:" + artifact.resolve("application.yaml");
      }
      try (FileSystem jar = FileSystems.newFileSystem(artifact)) {
        Path extracted =
            Files.createTempFile(application.getSimpleName() + "-", "-application.yaml");
        extracted.toFile().deleteOnExit();
        Files.copy(jar.getPath("application.yaml"), extracted, StandardCopyOption.REPLACE_EXISTING);
        return "file:" + extracted;
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not locate the application.yaml inside " + application.getName(), e);
    }
  }

  /**
   * Neither service creates a bucket — production provisions them, so no service's credentials need
   * {@code s3:CreateBucket} — which is why they are created here rather than on first use.
   */
  private static void createBucket(ObjectStoreFixture objectStore, String bucket) {
    try (S3Client s3 = objectStore.createS3Client()) {
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
      // A bucket per JVM; nothing else can have created it, but tolerating this costs nothing.
    }
  }

  /**
   * Applies the Task 4.1 topology by <b>running the production script</b>, {@code
   * infrastructure/compose/rabbitmq/declare-topology.sh} — the same one the compose stack and every
   * deployment run, and the same one {@code CdcJobIntegrationTest} runs.
   *
   * <p>Declaring the exchange and queues from Java here would work and would be shorter. It would
   * also freeze a copy of the quorum-queue type, the dead-letter routing and the delivery limit at
   * whatever they were the day it was written: the script could change and this stack would keep
   * passing against the old shape. The script is idempotent by design, so running it costs a few
   * HTTP calls.
   *
   * <p>The teardown before it stays in Java. The broker is shared and reused, and other suites
   * deliberately leave conflicting objects behind (see {@code RabbitMqTopologyIntegrationTest}),
   * which the script itself is right to treat as a hard failure rather than tidy away. The CDC
   * job's own exclusive locker queue is deliberately not touched — RabbitMQ removes it when its
   * owning connection goes, and deleting it under a running job is refused (RESOURCE_LOCKED) for
   * good reason.
   */
  private void declareAmqpTopology(RabbitMqFixture rabbit) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getAmqpPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection()) {
      deleteQuietly(connection, BRIDGE_DLQ);
      for (String queue : pipelineQueues()) {
        deleteQuietly(connection, queue);
      }
      deleteQuietly(connection, "ledger.entries.dlq");
      try (Channel channel = connection.createChannel()) {
        channel.exchangeDelete(EXCHANGE);
      } catch (Exception e) {
        LOGGER.debug("no {} exchange to remove", EXCHANGE, e);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not clear the AMQP topology", e);
    }

    try {
      ProcessBuilder builder =
          new ProcessBuilder(
                  "/bin/sh",
                  RepositoryRoot.path("infrastructure/compose/rabbitmq/declare-topology.sh"))
              .redirectErrorStream(true);
      Map<String, String> environment = builder.environment();
      environment.put(
          "RABBITMQ_MANAGEMENT_URL",
          "http://" + rabbit.getHost() + ":" + rabbit.getManagementPort());
      environment.put("RABBITMQ_DEFAULT_USER", rabbit.getUsername());
      environment.put("RABBITMQ_DEFAULT_PASS", rabbit.getPassword());
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException("declare-topology.sh exited " + exit + ":\n" + output);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted declaring the AMQP topology", e);
    } catch (IOException e) {
      throw new IllegalStateException("could not run declare-topology.sh", e);
    }
  }

  private static void deleteQuietly(Connection connection, String queue) {
    try (Channel channel = connection.createChannel()) {
      channel.queueDelete(queue);
    } catch (Exception e) {
      LOGGER.debug("no queue {} to remove", queue, e);
    }
  }

  /**
   * The stock connector, under the production supervisor script, with nothing about it altered for
   * the test. {@code tigerbeetle amqp} resolves neither {@code --addresses} nor {@code --host}
   * through DNS, so the job is pointed at the fixtures' bridge-network IPs and their in-container
   * ports rather than the host-mapped ones.
   */
  private void startCdcJob(TigerBeetleFixture tigerBeetle, RabbitMqFixture rabbit) {
    String tigerBeetleAddress = bridgeIp(tigerBeetle.getContainerId()) + ":3000";
    String rabbitAddress = bridgeIp(rabbit.getContainerId()) + ":5672";
    GenericContainer<?> job =
        new GenericContainer<>(ContainerImages.TIGERBEETLE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(RepositoryRoot.path("infrastructure/compose/cdc/run.sh")),
                "/run.sh")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint("/bin/sh", "/run.sh");
                  // Same as TigerBeetleFixture: the io_uring probe fails under default seccomp.
                  cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
                })
            .withEnv("CDC_TIGERBEETLE_ADDRESSES", tigerBeetleAddress)
            .withEnv("CDC_TIGERBEETLE_CLUSTER_ID", "0")
            .withEnv("CDC_AMQP_HOST", rabbitAddress)
            .withEnv("CDC_AMQP_USER", rabbit.getUsername())
            .withEnv("CDC_AMQP_PASSWORD", rabbit.getPassword())
            .withEnv("CDC_AMQP_VHOST", "/")
            .withEnv("CDC_PUBLISH_EXCHANGE", EXCHANGE)
            .withEnv("CDC_RETRY_INTERVAL_SECONDS", "1")
            // See the class javadoc: stream from (nearly) now, not from the shared ledger's
            // beginning. The minute of margin is not slack for slow startup -- it is because
            // this is the HOST clock and a TigerBeetle timestamp comes from the CONTAINER's.
            // On a Docker VM whose clock has drifted backwards (routine after a laptop sleeps)
            // a cursor taken from the host sits above every timestamp this run then writes, the
            // connector skips all of them, and the only symptom is three silent convergence
            // timeouts. Replaying a minute of the shared ledger costs nothing: the projection
            // is idempotent and no assertion here is a total.
            .withEnv(
                "CDC_TIMESTAMP_LAST",
                Long.toString((System.currentTimeMillis() - 60_000L) * 1_000_000L))
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("cdc-job"))
            .waitingFor(
                Wait.forLogMessage(".*starting `tigerbeetle amqp`.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));
    job.start();
    // Testcontainers' reaper would collect it anyway; stopping it explicitly releases the
    // exclusive locker queue promptly, so the next JVM's job wins the race immediately.
    Runtime.getRuntime().addShutdownHook(new Thread(job::stop));
  }

  private static String bridgeIp(String containerId) {
    return DockerClientFactory.instance()
        .client()
        .inspectContainerCmd(containerId)
        .exec()
        .getNetworkSettings()
        .getNetworks()
        .values()
        .stream()
        .map(network -> network.getIpAddress())
        .filter(ip -> ip != null && !ip.isBlank())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no IP for container " + containerId));
  }
}
