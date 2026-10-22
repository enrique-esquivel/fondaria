package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 4.2 — CDC job container. Runs the real {@code cdc/run.sh} wrapping the stock {@code
 * tigerbeetle amqp} connector against the shared TigerBeetle and RabbitMQ fixtures, and asserts on
 * what actually lands on {@code bridge.ledger} and on RabbitMQ's own state — never on a
 * reimplemented connector, and never on a retry count this test invented.
 *
 * <p>The TigerBeetle ledger and the RabbitMQ broker are reused across runs, so every assertion is
 * scoped to transfer ids this test generated ({@code docs/ENGINEERING-RULES.md} §4). "Full history"
 * in the replay test means "contains every id I created", never a total count.
 */
@Tag("integration")
class CdcJobIntegrationTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(CdcJobIntegrationTest.class);

  private static final Path REPO_ROOT = findRepoRoot();
  private static final Path RUN_SH = REPO_ROOT.resolve("infrastructure/compose/cdc/run.sh");
  private static final Path TOPOLOGY_SH =
      REPO_ROOT.resolve("infrastructure/compose/rabbitmq/declare-topology.sh");

  private static final String EXCHANGE = "tigerbeetle";
  private static final String BRIDGE_QUEUE = "bridge.ledger";
  private static final String BRIDGE_DLQ = "bridge.ledger.dlq";
  // Cluster id 0 (the fixture's default) — `tigerbeetle amqp` builds the name as
  // "tigerbeetle.internal.locker.<cluster_id>" itself. See rabbitmq/README.md.
  private static final String LOCKER_QUEUE = "tigerbeetle.internal.locker.0";

  /**
   * How long a {@code tigerbeetle amqp} instance may take to reach a steady state — connected,
   * holding or losing the locker race, and streaming — before a wait for it is a failure.
   *
   * <p><b>Sized for the runner, not for a laptop.</b> These tests run two or three job containers
   * beside Postgres, RabbitMQ and TigerBeetle on a two-CPU, eight-gigabyte GitHub runner. The
   * previous ad-hoc ten- and fifteen-second waits fit that on a developer's machine and did not fit
   * it under contention: three of the five tests here failed on a green pull request, on timing
   * alone, with nothing wrong in the code they cover. A test that reports scheduler contention as a
   * defect is worse than no test — it trains the reader to re-run rather than read.
   *
   * <p><b>Generous, not absent.</b> Every wait still fails, and none of these is a wall-clock
   * assertion about the product: the one figure this suite really measures — {@code failoverMs}
   * against its thirty-second budget — is untouched, and so is every assertion about what actually
   * landed on the queue. Waiting longer costs nothing on a healthy run, because these poll and
   * return as soon as the condition holds.
   */
  private static final Duration STREAM_SETTLE = Duration.ofSeconds(60);

  private static TigerBeetleFixture tigerbeetle;
  private static RabbitMqFixture rabbit;
  private static Client ledgerClient;
  private static HttpClient http;
  private static String authHeader;

  // `tigerbeetle amqp` resolves NEITHER --addresses NOR --host through DNS — both want a literal
  // IPv4/IPv6 address. The CDC job container and the reused fixtures all sit on Docker's default
  // bridge network, where containers reach each other by IP (just not by name), so the job is
  // pointed at the fixtures' bridge IPs and their in-container ports, not the host-mapped ports.
  private static String tigerbeetleAddress;
  private static String rabbitAmqpAddress;

  private final List<GenericContainer<?>> startedJobs = new ArrayList<>();

  @BeforeAll
  static void setUpClass() throws Exception {
    tigerbeetle = TigerBeetleFixture.getInstance();
    rabbit = RabbitMqFixture.getInstance();
    ledgerClient = tigerbeetle.getClient();

    tigerbeetleAddress = bridgeIp(tigerbeetle.getContainerId()) + ":3000";
    rabbitAmqpAddress = bridgeIp(rabbit.getContainerId()) + ":5672";
    LOGGER.info(
        "CDC job will reach TigerBeetle at {} and RabbitMQ at {}",
        tigerbeetleAddress,
        rabbitAmqpAddress);

    http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (rabbit.getUsername() + ":" + rabbit.getPassword())
                        .getBytes(StandardCharsets.UTF_8));

    // Another integration test class (RabbitMqTopologyIntegrationTest) deliberately leaves a
    // conflicting `bridge.ledger` behind, and the shared broker is reused, so tear the topology
    // objects down before re-declaring them — this test owns the topology it needs.
    deleteQueue(BRIDGE_QUEUE);
    deleteQueue(BRIDGE_DLQ);
    deleteQueue(LOCKER_QUEUE);
    mgmt("DELETE", "/api/exchanges/%2F/" + EXCHANGE, null);

    // Apply the Task 4.1 topology (idempotent). The CDC job validates the exchange passively and
    // exits if it is missing, so it must exist before any job starts.
    assertThat(runTopologyScript()).as("declare-topology.sh exit code").isEqualTo(0);
  }

  @AfterAll
  static void tearDownClass() {
    if (ledgerClient != null) {
      ledgerClient.close();
    }
  }

  @BeforeEach
  void resetBrokerState() throws Exception {
    // Leave the durable progress queue alone between tests — "resume from last ack" depends on it
    // — but start each test with an empty bridge queue and no stale lock.
    deleteQueue(LOCKER_QUEUE);
    purgeQueue(BRIDGE_QUEUE);
    purgeQueue(BRIDGE_DLQ);
  }

  @AfterEach
  void stopJobs() {
    for (GenericContainer<?> job : startedJobs) {
      try {
        job.stop();
      } catch (RuntimeException e) {
        LOGGER.warn("failed to stop CDC job container", e);
      }
    }
    startedJobs.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // AC1 — a contribution posted via the ledger appears on bridge.ledger within 2 seconds
  // ---------------------------------------------------------------------------------------------

  @Test
  void aLedgerMovementAppearsOnBridgeLedgerWithinTwoSeconds() throws Exception {
    startCdcJob("job", Map.of());

    try (BridgeConsumer consumer = new BridgeConsumer()) {
      // Let the job catch the ledger up to now (drain whatever history replays) so we measure
      // steady-state lag, not cold-start replay.
      consumer.awaitQuiet(Duration.ofSeconds(2));
      consumer.clear();

      long transferId = freshId();
      long start = System.nanoTime();
      postTransfer(transferId);

      pollUntilTrue(
          Duration.ofSeconds(5),
          Duration.ofMillis(50),
          () -> !consumer.bodiesContaining(Long.toString(transferId)).isEmpty());
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      LOGGER.info("CDC steady-state lag for a single transfer: {} ms", elapsedMs);
      assertThat(elapsedMs).as("end-to-end lag in ms").isLessThan(2_000);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC2 — killing and restarting resumes without gaps
  // ---------------------------------------------------------------------------------------------

  @Test
  void killingAndRestartingResumesWithoutGaps() throws Exception {
    GenericContainer<?> job = startCdcJob("job", Map.of());

    try (BridgeConsumer consumer = new BridgeConsumer()) {
      consumer.awaitQuiet(Duration.ofSeconds(2));
      consumer.clear();

      long before = freshId();
      postTransfer(before);
      pollUntilTrue(
          STREAM_SETTLE,
          Duration.ofMillis(100),
          () -> !consumer.bodiesContaining(Long.toString(before)).isEmpty());
    }

    // Kill the job, then post a transfer while nothing is streaming.
    job.stop();
    startedJobs.remove(job);
    long during = freshId();
    postTransfer(during);

    // Restart. With no replay override it resumes from the progress queue, so the transfer
    // posted while it was down must still arrive.
    startCdcJob("job-restarted", Map.of());
    try (BridgeConsumer consumer = new BridgeConsumer()) {
      long after = freshId();
      postTransfer(after);

      pollUntilAsserted(
          STREAM_SETTLE,
          Duration.ofMillis(200),
          () -> {
            assertThat(consumer.bodiesContaining(Long.toString(during)))
                .as("transfer posted while the job was down is not skipped")
                .isNotEmpty();
            assertThat(consumer.bodiesContaining(Long.toString(after)))
                .as("transfer posted after restart arrives")
                .isNotEmpty();
          });
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC3 — killing the active instance: the standby claims the lock and resumes; failover measured
  // ---------------------------------------------------------------------------------------------

  @Test
  void killingTheActiveInstanceFailsOverToTheStandbyAndFailoverTimeIsRecorded() throws Exception {
    GenericContainer<?> active = startCdcJob("active", Map.of());
    GenericContainer<?> standby = startCdcJob("standby", Map.of());

    try (BridgeConsumer consumer = new BridgeConsumer()) {
      consumer.awaitQuiet(Duration.ofSeconds(2));

      // Polled, not asserted once. Two job containers were started moments ago and the locker
      // queue is declared by whichever wins the race -- so this is a wait for the topology to
      // settle, and a fixed two-second pause is a guess about how long that takes. It was the
      // wrong guess on a contended runner, where this failed on a green pull request.
      pollUntilAsserted(
          STREAM_SETTLE,
          Duration.ofMillis(200),
          () -> {
            assertThat(queueNamesMatching("tigerbeetle.internal.locker"))
                .as("exactly one locker queue while active + standby run")
                .containsExactly(LOCKER_QUEUE);
            assertThat(lockerQueueHeldExclusively())
                .as("locker queue is held exclusively before failover")
                .isTrue();
          });
      consumer.clear();

      long killAt = System.nanoTime();
      active.stop();
      startedJobs.remove(active);

      long failoverMs = awaitFailover(consumer, killAt);
      LOGGER.info(
          "CDC failover: standby resumed streaming {} ms after the active instance was killed",
          failoverMs);

      assertThat(failoverMs).as("failover time in ms").isLessThan(30_000);
      assertThat(standby.isRunning()).as("standby is still the process, now active").isTrue();
      assertThat(queueNamesMatching("tigerbeetle.internal.locker"))
          .as("still exactly one locker queue after failover")
          .containsExactly(LOCKER_QUEUE);
      assertThat(lockerQueueHeldExclusively())
          .as("locker queue held exclusively by the new owner after failover")
          .isTrue();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC4 + AC5 — at most one instance streams (inspected on RabbitMQ), and a third instance keeps
  // retrying rather than crashing out of the loop
  // ---------------------------------------------------------------------------------------------

  @Test
  void withThreeInstancesOnlyOneStreamsAndTheOthersKeepRetrying() throws Exception {
    GenericContainer<?> a = startCdcJob("a", Map.of());
    GenericContainer<?> b = startCdcJob("b", Map.of());
    GenericContainer<?> c = startCdcJob("c", Map.of());

    try (BridgeConsumer consumer = new BridgeConsumer()) {
      consumer.awaitQuiet(Duration.ofSeconds(3));

      // Inspected on RabbitMQ, not inferred: exactly one locker queue, held exclusively.
      assertThat(queueNamesMatching("tigerbeetle.internal.locker"))
          .as("exactly one locker queue exists")
          .containsExactly(LOCKER_QUEUE);
      assertThat(lockerQueueHeldExclusively())
          .as("the locker queue is held exclusively by one connection")
          .isTrue();

      // At most one instance streams: a single posted transfer produces exactly one message, not
      // one per running instance.
      consumer.clear();
      long id = freshId();
      postTransfer(id);
      pollUntilTrue(
          STREAM_SETTLE,
          Duration.ofMillis(100),
          () -> !consumer.bodiesContaining(Long.toString(id)).isEmpty());
      // Give any second/third publisher a chance to duplicate before asserting the count.
      TimeUnit.SECONDS.sleep(3);
      assertThat(consumer.bodiesContaining(Long.toString(id)))
          .as("only one instance published the transfer")
          .hasSize(1);

      // The two losers keep retrying — their run.sh loop, and therefore the container, is alive.
      long stillRunning = List.of(a, b, c).stream().filter(GenericContainer::isRunning).count();
      assertThat(stillRunning).as("all three instances still running").isEqualTo(3);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AC6 — --timestamp-last=0 replays full history
  // ---------------------------------------------------------------------------------------------

  @Test
  void timestampLastZeroReplaysFullHistory() throws Exception {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(freshId());
    }

    // A normal job streams the transfers, advancing the progress queue well past 0.
    GenericContainer<?> primer = startCdcJob("primer", Map.of());
    try (BridgeConsumer consumer = new BridgeConsumer()) {
      consumer.awaitQuiet(Duration.ofSeconds(2));
      consumer.clear();
      for (long id : ids) {
        postTransfer(id);
      }
      pollUntilAsserted(
          STREAM_SETTLE,
          Duration.ofMillis(200),
          () -> {
            for (long id : ids) {
              assertThat(consumer.bodiesContaining(Long.toString(id)))
                  .as("primer streamed transfer " + id)
                  .isNotEmpty();
            }
          });
    }
    primer.stop();
    startedJobs.remove(primer);

    // Fresh bridge queue, then replay from the beginning despite the non-zero progress queue.
    purgeQueue(BRIDGE_QUEUE);
    startCdcJob("replay", Map.of("CDC_TIMESTAMP_LAST", "0"));

    try (BridgeConsumer consumer = new BridgeConsumer()) {
      pollUntilAsserted(
          Duration.ofSeconds(90),
          Duration.ofMillis(250),
          () -> {
            for (long id : ids) {
              assertThat(consumer.bodiesContaining(Long.toString(id)))
                  .as("replay from 0 re-delivered transfer " + id)
                  .isNotEmpty();
            }
          });
    }
  }

  // ---------------------------------------------------------------------------------------------
  // CDC job container
  // ---------------------------------------------------------------------------------------------

  private GenericContainer<?> startCdcJob(String name, Map<String, String> overrides) {
    GenericContainer<?> job =
        new GenericContainer<>(ContainerImages.TIGERBEETLE)
            .withCopyFileToContainer(MountableFile.forHostPath(RUN_SH.toString(), 0755), "/run.sh")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint("/bin/sh", "/run.sh");
                  // Same as TigerBeetleFixture: the io_uring probe fails under default seccomp.
                  cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
                })
            .withEnv("CDC_TIGERBEETLE_ADDRESSES", tigerbeetleAddress)
            .withEnv("CDC_TIGERBEETLE_CLUSTER_ID", "0")
            .withEnv("CDC_AMQP_HOST", rabbitAmqpAddress)
            .withEnv("CDC_AMQP_USER", rabbit.getUsername())
            .withEnv("CDC_AMQP_PASSWORD", rabbit.getPassword())
            .withEnv("CDC_AMQP_VHOST", "/")
            .withEnv("CDC_PUBLISH_EXCHANGE", EXCHANGE)
            .withEnv("CDC_RETRY_INTERVAL_SECONDS", "1")
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("cdc-" + name))
            .waitingFor(
                Wait.forLogMessage(".*starting `tigerbeetle amqp`.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)));
    overrides.forEach(job::withEnv);
    job.start();
    startedJobs.add(job);
    return job;
  }

  // ---------------------------------------------------------------------------------------------
  // TigerBeetle — post a transfer on a throwaway ledger with fresh account ids
  // ---------------------------------------------------------------------------------------------

  /** A positive id well above 2^53 so it is distinctive in the CDC JSON, number or string. */
  private static long freshId() {
    return ThreadLocalRandom.current().nextLong(100_000_000_000_000_000L, Long.MAX_VALUE);
  }

  private static void postTransfer(long transferId) throws Exception {
    int ledger = ThreadLocalRandom.current().nextInt(900_000_000, 999_999_999);
    long debit = freshId();
    long credit = freshId();

    AccountBatch accounts = new AccountBatch(2);
    accounts.add();
    accounts.setId(debit);
    accounts.setLedger(ledger);
    accounts.setCode(1);
    accounts.setFlags(AccountFlags.NONE);
    accounts.add();
    accounts.setId(credit);
    accounts.setLedger(ledger);
    accounts.setCode(1);
    accounts.setFlags(AccountFlags.NONE);
    CreateAccountResultBatch accountResults = ledgerClient.createAccounts(accounts);
    while (accountResults.next()) {
      assertThat(accountResults.getStatus())
          .as("account creation status")
          .isIn(CreateAccountStatus.Created, CreateAccountStatus.Exists);
    }

    TransferBatch transfers = new TransferBatch(1);
    transfers.add();
    transfers.setId(transferId);
    transfers.setDebitAccountId(debit);
    transfers.setCreditAccountId(credit);
    transfers.setLedger(ledger);
    transfers.setCode(1);
    transfers.setAmount(1);
    transfers.setFlags(TransferFlags.NONE);
    CreateTransferResultBatch transferResults = ledgerClient.createTransfers(transfers);
    while (transferResults.next()) {
      assertThat(transferResults.getStatus())
          .as("transfer creation status")
          .isEqualTo(CreateTransferStatus.Created);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // bridge.ledger consumer
  // ---------------------------------------------------------------------------------------------

  private final class BridgeConsumer implements AutoCloseable {
    private final Connection connection;
    private final Channel channel;
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private volatile long lastDeliveryNanos = System.nanoTime();

    BridgeConsumer() throws Exception {
      ConnectionFactory factory = new ConnectionFactory();
      factory.setHost(rabbit.getHost());
      factory.setPort(rabbit.getAmqpPort());
      factory.setUsername(rabbit.getUsername());
      factory.setPassword(rabbit.getPassword());
      this.connection = factory.newConnection();
      this.channel = connection.createChannel();
      this.channel.basicConsume(
          BRIDGE_QUEUE,
          true,
          new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                String consumerTag,
                Envelope envelope,
                AMQP.BasicProperties properties,
                byte[] body) {
              bodies.add(new String(body, StandardCharsets.UTF_8));
              lastDeliveryNanos = System.nanoTime();
            }
          });
    }

    List<String> bodiesContaining(String needle) {
      return bodies.stream().filter(b -> b.contains(needle)).toList();
    }

    void clear() {
      bodies.clear();
    }

    /** Block until no message has arrived for {@code quietFor}. */
    void awaitQuiet(Duration quietFor) {
      pollUntilTrue(
          Duration.ofSeconds(120),
          Duration.ofMillis(200),
          () -> System.nanoTime() - lastDeliveryNanos > quietFor.toNanos());
    }

    @Override
    public void close() {
      try {
        channel.close();
      } catch (Exception ignored) {
        // best effort
      }
      try {
        connection.close();
      } catch (Exception ignored) {
        // best effort
      }
    }
  }

  /**
   * Post transfers on a loop until one of them shows up on bridge.ledger; returns milliseconds from
   * {@code killAtNanos} to that first captured delivery.
   */
  private long awaitFailover(BridgeConsumer consumer, long killAtNanos) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
    while (System.nanoTime() < deadline) {
      long id = freshId();
      postTransfer(id);
      long checkUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (System.nanoTime() < checkUntil) {
        if (!consumer.bodiesContaining(Long.toString(id)).isEmpty()) {
          return (System.nanoTime() - killAtNanos) / 1_000_000;
        }
        TimeUnit.MILLISECONDS.sleep(100);
      }
    }
    throw new AssertionError("standby did not resume streaming within 35s of the active kill");
  }

  // ---------------------------------------------------------------------------------------------
  // polling helpers (no Awaitility dependency — Task 4.2 names none)
  // ---------------------------------------------------------------------------------------------

  private static void pollUntilTrue(
      Duration timeout, Duration interval, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(interval);
    }
    throw new AssertionError("condition not met within " + timeout);
  }

  private static void pollUntilAsserted(
      Duration timeout, Duration interval, ThrowingRunnable assertion) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Throwable last = null;
    while (System.nanoTime() < deadline) {
      try {
        assertion.run();
        return;
      } catch (Throwable t) {
        last = t;
      }
      sleep(interval);
    }
    throw new AssertionError("assertion did not pass within " + timeout, last);
  }

  private static void sleep(Duration d) {
    try {
      TimeUnit.NANOSECONDS.sleep(d.toNanos());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  // ---------------------------------------------------------------------------------------------
  // RabbitMQ management API helpers
  // ---------------------------------------------------------------------------------------------

  private static int runTopologyScript() throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder("/bin/sh", TOPOLOGY_SH.toString()).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put(
        "RABBITMQ_MANAGEMENT_URL", "http://" + rabbit.getHost() + ":" + rabbit.getManagementPort());
    env.put("RABBITMQ_DEFAULT_USER", rabbit.getUsername());
    env.put("RABBITMQ_DEFAULT_PASS", rabbit.getPassword());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      LOGGER.error("declare-topology.sh failed:\n{}", output);
    }
    return exit;
  }

  @SuppressWarnings("unchecked")
  private List<String> queueNamesMatching(String prefix) throws IOException, InterruptedException {
    HttpResponse<String> response = mgmt("GET", "/api/queues/%2F", null);
    assertThat(response.statusCode()).isEqualTo(200);
    List<Map<String, Object>> queues = (List<Map<String, Object>>) new Yaml().load(response.body());
    return queues.stream()
        .map(q -> String.valueOf(q.get("name")))
        .filter(n -> n.startsWith(prefix))
        .sorted()
        .toList();
  }

  /**
   * True when the locker queue currently exists as an <em>exclusive</em> queue — i.e. exactly one
   * connection holds it. {@code tigerbeetle amqp} declares it {@code exclusive=true} and never
   * consumes from it, so "consumers" is always 0; the exclusive flag plus the queue's existence
   * (RabbitMQ deletes an exclusive queue the instant its owning connection drops) is the single
   * "held by one connection" signal, and RESOURCE_LOCKED refusals for every other connection are
   * the enforcement.
   */
  @SuppressWarnings("unchecked")
  private boolean lockerQueueHeldExclusively() throws IOException, InterruptedException {
    HttpResponse<String> response = mgmt("GET", "/api/queues/%2F/" + LOCKER_QUEUE, null);
    if (response.statusCode() != 200) {
      return false;
    }
    Map<String, Object> body = (Map<String, Object>) new Yaml().load(response.body());
    return Boolean.TRUE.equals(body.get("exclusive"));
  }

  private static void purgeQueue(String queue) throws IOException, InterruptedException {
    if (mgmt("GET", "/api/queues/%2F/" + queue, null).statusCode() == 200) {
      mgmt("DELETE", "/api/queues/%2F/" + queue + "/contents", null);
    }
  }

  private static void deleteQueue(String queue) throws IOException, InterruptedException {
    mgmt("DELETE", "/api/queues/%2F/" + queue, null);
  }

  private static HttpResponse<String> mgmt(String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.BodyPublisher publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://" + rabbit.getHost() + ":" + rabbit.getManagementPort() + path))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** The container's IP on whatever Docker network it is attached to (the default bridge here). */
  private static String bridgeIp(String containerId) {
    var networks =
        DockerClientFactory.instance()
            .client()
            .inspectContainerCmd(containerId)
            .exec()
            .getNetworkSettings()
            .getNetworks();
    return networks.values().stream()
        .map(n -> n.getIpAddress())
        .filter(ip -> ip != null && !ip.isBlank())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no IP for container " + containerId));
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("infrastructure/compose/docker-compose.yml"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from " + Paths.get(""));
  }
}
