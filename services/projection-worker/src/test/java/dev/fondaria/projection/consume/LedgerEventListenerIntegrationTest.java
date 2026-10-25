package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import dev.fondaria.ledger.LedgerEntryEvent;
import dev.fondaria.ledger.LedgerTransferEvent;
import dev.fondaria.projection.ProjectionWorkerApplication;
import dev.fondaria.projection.health.ProjectionHaltHealthIndicator;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The consumer skeleton (Task 5.3, ADR-0022) against the real {@link ProjectionWorkerApplication}
 * context and the shared RabbitMQ / Postgres fixtures.
 *
 * <p>The real {@link JdbcProjectionSink} is replaced by a programmable sink so a test can make the
 * projection fail transiently, delay, or observe how many events are in flight at once. Contexts
 * are started and stopped per test with a {@link SpringApplicationBuilder}, so shutdown behaviour
 * is testable.
 *
 * <p>Fixtures are reused across runs (docs/ENGINEERING-RULES.md §4). Each test creates its
 * <b>own</b> input exchanges, queues, and dead-letter queue (a per-test nonce), so a
 * permanently-poison message one test produces can never be re-read by the next.
 */
@Tag("integration")
class LedgerEventListenerIntegrationTest {

  private static RabbitMqFixture rabbit;

  private static final Set<String> TRANSFERS_SEEN = ConcurrentHashMap.newKeySet();
  private static final List<String> ACCOUNTS_SEEN = new CopyOnWriteArrayList<>();
  private static final List<String> ENTRY_CONSUMER_IDS = new CopyOnWriteArrayList<>();
  private static final Map<String, ProjectionCheckpoint.ConsumerRef> CONSUMER_BY_TRANSFER =
      new ConcurrentHashMap<>();
  private static final AtomicInteger ACCEPTED_OK = new AtomicInteger();
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
  private static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
  private static final AtomicInteger FAILURES_REMAINING = new AtomicInteger();
  private static volatile Duration ENTRY_DELAY = Duration.ZERO;
  private static final List<Integer> BATCH_SIZES = new CopyOnWriteArrayList<>();

  private String transfersExchange;
  private String entriesExchange;
  private String deadLetterQueue;
  private String transfersQueue;
  private String entriesQueuePrefix;
  private ConfigurableApplicationContext context;
  private ConfigurableApplicationContext otherReplica;
  // Unique per test: the halt flag is a row in the reused database, and a halt one test leaves
  // active under a shared name would stop every later run's worker (ADR-0032 §2).
  private String haltName;

  @BeforeAll
  static void startFixtures() {
    PostgresFixture.getInstance();
    rabbit = RabbitMqFixture.getInstance();
  }

  @BeforeEach
  void freshTopicsAndSink() throws Exception {
    String nonce = UUID.randomUUID().toString().substring(0, 8);
    haltName = "listener-test-" + nonce;
    transfersExchange = "test.transfers." + nonce;
    entriesExchange = "test.entries." + nonce;
    deadLetterQueue = "test.dlq." + nonce;
    transfersQueue = "test.proj.transfers." + nonce;
    entriesQueuePrefix = "test.proj.entries." + nonce;

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.exchangeDeclare(transfersExchange, "fanout", true);
      channel.exchangeDeclare(entriesExchange, "x-consistent-hash", true, false, Map.of());
      channel.queueDeclare(deadLetterQueue, true, false, false, null);
      channel.queueDeclare(transfersQueue, true, false, false, null);
      channel.queueBind(transfersQueue, transfersExchange, "");
      for (int i = 0; i < 4; i++) {
        String q = entriesQueuePrefix + "." + i;
        channel.queueDeclare(q, true, false, false, null);
        channel.queueBind(q, entriesExchange, "10");
      }
    }

    TRANSFERS_SEEN.clear();
    ACCOUNTS_SEEN.clear();
    ENTRY_CONSUMER_IDS.clear();
    CONSUMER_BY_TRANSFER.clear();
    ACCEPTED_OK.set(0);
    IN_FLIGHT.set(0);
    MAX_IN_FLIGHT.set(0);
    FAILURES_REMAINING.set(0);
    ENTRY_DELAY = Duration.ZERO;
    BATCH_SIZES.clear();
  }

  @AfterEach
  void stopContext() throws Exception {
    if (context != null) {
      context.close();
      context = null;
    }
    if (otherReplica != null) {
      otherReplica.close();
      otherReplica = null;
    }
    try (java.sql.Connection c = postgresConnection();
        java.sql.PreparedStatement clear =
            c.prepareStatement(
                "UPDATE workflow.projection_halt SET cleared_at = now(), cleared_by = 'test',"
                    + " clear_note = 'test cleanup' WHERE projection_name = ?"
                    + " AND cleared_at IS NULL")) {
      clear.setString(1, haltName);
      clear.executeUpdate();
    }
  }

  // -------------------------------------------------------------------------------------------
  // AC: a transient failure retries and then succeeds
  // -------------------------------------------------------------------------------------------

  // -------------------------------------------------------------------------------------------
  // ADR-0032 §1: each delivery names the consumer whose checkpoint row it advances
  // -------------------------------------------------------------------------------------------

  @Test
  void eachDeliveryNamesItsInstanceAndBrokerConsumerTag() {
    context = start();

    String id = freshId();
    produceTransfer(transfersExchange, id, transferJson(id));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(CONSUMER_BY_TRANSFER).containsKey(id));
    ProjectionCheckpoint.ConsumerRef consumer = CONSUMER_BY_TRANSFER.get(id);
    // <HOSTNAME>/<tag the broker assigned this consumer> — unique per consumer, so one writer per
    // checkpoint row. A null tag would collapse every consumer onto one row again.
    assertThat(consumer.id()).startsWith("listener-test-pod/amq.ctag-");
    assertThat(consumer.queue()).isEqualTo(transfersQueue);
  }

  @Test
  void transientFailuresRetryAndSucceed() {
    FAILURES_REMAINING.set(2);
    context = start();

    String id = freshId();
    produceTransfer(transfersExchange, id, transferJson(id));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).contains(id));
    assertThat(ACCEPTED_OK.get()).isEqualTo(1);
    assertThat(context.getBean(ProjectionHalt.class).isHalted()).isFalse();
    assertThat(poisonCount()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // AC: two events for the same account are never processed concurrently
  // -------------------------------------------------------------------------------------------

  @Test
  void twoEventsForTheSameAccountAreNeverConcurrent() {
    ENTRY_DELAY = Duration.ofMillis(200);
    context = start();

    String account = freshId();
    for (int i = 0; i < 6; i++) {
      produceEntry(entriesExchange, account, entryJson(account));
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(ACCOUNTS_SEEN).hasSize(6));
    assertThat(MAX_IN_FLIGHT.get())
        .as("one account's events are drained by exactly one thread")
        .isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // ADR-0034 amendment (row 26w): one exclusive consumer per entries queue, across replicas
  // -------------------------------------------------------------------------------------------

  /**
   * Row 26u's load test (#174): with every consumer of every replica reading every queue, two legs
   * of one holding were projected by two batches at once. Each entries queue now has one exclusive
   * consumer in the whole deployment, so across two replicas one account's events are still drained
   * by one thread. The events are spaced beyond the batch timeout so that each is its own batch:
   * competing consumers would take them concurrently.
   */
  @Test
  void twoReplicasNeverProcessOneAccountConcurrently() throws Exception {
    ENTRY_DELAY = Duration.ofMillis(300);
    context = start("replica-a");
    otherReplica = start("replica-b");
    await().atMost(Duration.ofSeconds(20)).until(() -> containersRunning(otherReplica));

    String account = freshId();
    for (int i = 0; i < 6; i++) {
      produceEntry(entriesExchange, account, entryJson(account));
      Thread.sleep(150);
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(ACCOUNTS_SEEN).hasSize(6));
    assertThat(MAX_IN_FLIGHT.get())
        .as("one account's events are drained by exactly one thread across both replicas")
        .isEqualTo(1);
    assertThat(ENTRY_CONSUMER_IDS.stream().distinct())
        .as("one consumer in the deployment owns the account's queue")
        .hasSize(1);
  }

  /**
   * The standby is not idle capacity: when the replica that owns an entries queue stops, the other
   * replica's refused consumer is restarted on the recovery interval and takes the queue over, and
   * nothing published meanwhile is lost.
   *
   * <p>Scope: a graceful close that has completed before the next events are published. It does not
   * cover a batch in flight at the handover or an owner whose connection drops without cancelling;
   * both rely on redelivery and ADR-0024's idempotent, lock-guarded sink, tested in {@code
   * JdbcProjectionSinkIntegrationTest} and {@code CrashAndReplayIntegrationTest}.
   */
  @Test
  void whenTheOwningReplicaStopsTheStandbyTakesItsQueueOver() throws Exception {
    context = start("replica-a");
    otherReplica = start("replica-b");
    await().atMost(Duration.ofSeconds(20)).until(() -> containersRunning(otherReplica));

    String account = freshId();
    produceEntry(entriesExchange, account, entryJson(account));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(ACCOUNTS_SEEN).hasSize(1));
    String owner = instanceOf(ENTRY_CONSUMER_IDS.get(0));
    String survivor = owner.equals("replica-a") ? "replica-b" : "replica-a";
    if (owner.equals("replica-a")) {
      context.close();
      context = null;
    } else {
      otherReplica.close();
      otherReplica = null;
    }

    for (int i = 0; i < 3; i++) {
      produceEntry(entriesExchange, account, entryJson(account));
    }

    await()
        .atMost(Duration.ofSeconds(40))
        .untilAsserted(() -> assertThat(ACCOUNTS_SEEN).hasSize(4));
    assertThat(ENTRY_CONSUMER_IDS.subList(1, 4))
        .as("the three events published after the owner stopped went to the standby")
        .allSatisfy(id -> assertThat(instanceOf(id)).isEqualTo(survivor));
  }

  private static String instanceOf(String consumerId) {
    return consumerId.substring(0, consumerId.indexOf('/'));
  }

  // -------------------------------------------------------------------------------------------
  // AC: clean shutdown loses no in-flight message
  // -------------------------------------------------------------------------------------------

  @Test
  void cleanShutdownLosesNoInFlightMessage() {
    ENTRY_DELAY = Duration.ofMillis(120);
    context = start();

    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String id = freshId();
      ids.add(id);
      produceTransfer(transfersExchange, id, transferJson(id));
    }

    // Let it commit a few, then stop the context while records are still in flight.
    await().atMost(Duration.ofSeconds(20)).until(() -> TRANSFERS_SEEN.size() >= 3);
    context.close();

    // A fresh instance resumes from the queue — nothing that was in flight and unacknowledged
    // at shutdown is lost.
    context = start();
    await()
        .atMost(Duration.ofSeconds(40))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).containsAll(ids));
  }

  // -------------------------------------------------------------------------------------------
  // AC: a poison message halts the worker and readiness reports DOWN
  // -------------------------------------------------------------------------------------------

  @Test
  void poisonMessageHaltsTheWorkerAndReadinessReportsDown() {
    context = start();

    produceEntry(entriesExchange, "acct", "{ this is not a ledger event");

    ProjectionHalt halt = context.getBean(ProjectionHalt.class);
    ProjectionHaltHealthIndicator readiness = context.getBean(ProjectionHaltHealthIndicator.class);
    RabbitListenerEndpointRegistry registry = context.getBean(RabbitListenerEndpointRegistry.class);

    await().atMost(Duration.ofSeconds(30)).until(halt::isHalted);
    assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(poisonCount()).isGreaterThanOrEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                registry.getListenerContainers().stream()
                    .noneMatch(MessageListenerContainer::isRunning));
    assertThat(dlqHasRecord()).isTrue();
    assertThat(
            context
                .getBean(MeterRegistry.class)
                .get("rabbitmq_publish_duration_seconds")
                .tag("destination", deadLetterQueue)
                .timer()
                .count())
        .isPositive();
  }

  // -------------------------------------------------------------------------------------------
  // ADR-0034: batches
  // -------------------------------------------------------------------------------------------

  @Test
  void deliveriesAlreadyQueuedArriveAsBatchesAndAreAllAcknowledged() throws Exception {
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 30; i++) {
      String id = freshId();
      ids.add(id);
      produceTransfer(transfersExchange, id, transferJson(id));
    }

    context = start("listener-test-pod", Map.of("PROJECTION_TRANSFER_CONSUMERS", "1"));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).containsAll(ids));
    assertThat(BATCH_SIZES)
        .as("thirty deliveries already waiting reach the sink in batches, not one by one")
        .anyMatch(size -> size > 1);
    context.close();
    context = null;
    assertThat(messagesIn(transfersQueue)).as("every batch was acknowledged").isZero();
  }

  @Test
  void aPoisonMessageInsideABatchCommitsOnlyWhatPrecedesIt() throws Exception {
    List<String> before = List.of(freshId(), freshId(), freshId());
    List<String> after = List.of(freshId(), freshId(), freshId());
    for (String id : before) {
      produceTransfer(transfersExchange, id, transferJson(id));
    }
    produceTransfer(transfersExchange, "poison", "{ this is not a ledger event");
    for (String id : after) {
      produceTransfer(transfersExchange, id, transferJson(id));
    }

    // One consumer: all seven deliveries reach one batch, so "before" and "after" are defined.
    context = start("listener-test-pod", Map.of("PROJECTION_TRANSFER_CONSUMERS", "1"));
    ProjectionHalt halt = context.getBean(ProjectionHalt.class);

    await().atMost(Duration.ofSeconds(30)).until(halt::isHalted);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).containsAll(before));
    assertThat(TRANSFERS_SEEN)
        .as("nothing after the poison message is projected ahead of it")
        .doesNotContainAnyElementsOf(after);
    assertThat(BATCH_SIZES.stream().mapToInt(Integer::intValue).sum())
        .as("only the three deliveries before the poison message reached the sink")
        .isEqualTo(3);
    assertThat(dlqHasRecord()).isTrue();
    context.close();
    context = null;
    assertThat(messagesIn(transfersQueue))
        .as("the poison message and the three after it stay queued, unacknowledged")
        .isEqualTo(4);
  }

  private long messagesIn(String queue) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      long deadline = System.currentTimeMillis() + 10_000;
      long count = channel.queueDeclarePassive(queue).getMessageCount();
      // Unacknowledged deliveries return to the queue once the closed consumer's channel is gone.
      while (System.currentTimeMillis() < deadline) {
        Thread.sleep(200);
        long now = channel.queueDeclarePassive(queue).getMessageCount();
        if (now == count) {
          return now;
        }
        count = now;
      }
      return count;
    }
  }

  // -------------------------------------------------------------------------------------------
  // ADR-0032 §2: a halt on one replica is a halt on every replica
  // -------------------------------------------------------------------------------------------

  @Test
  void aPoisonMessageOnOneReplicaHaltsTheOtherToo() throws Exception {
    context = start("replica-a");
    otherReplica = start("replica-b");
    await().atMost(Duration.ofSeconds(20)).until(() -> containersRunning(otherReplica));

    produceEntry(entriesExchange, "acct", "{ this is not a ledger event");

    // Whichever replica the poison reached raised the flag; the other read it and stopped.
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                context.getBean(ProjectionHalt.class).isHalted()
                    && otherReplica.getBean(ProjectionHalt.class).isHalted());
    for (ConfigurableApplicationContext replica : List.of(context, otherReplica)) {
      assertThat(replica.getBean(ProjectionHaltHealthIndicator.class).health().getStatus())
          .isEqualTo(Status.DOWN);
      await().atMost(Duration.ofSeconds(20)).until(() -> !containersRunning(replica));
    }

    List<Map<String, Object>> rows = haltRows();
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("halted_by")).isIn("replica-a", "replica-b");
    assertThat(row.get("reason_code")).isEqualTo("MALFORMED_EVENT");
    assertThat(row.get("source_queue").toString()).startsWith(entriesQueuePrefix);
    assertThat(row.get("cleared_at")).isNull();
  }

  @Test
  void aReplicaStartedDuringAHaltNeverConsumes() throws Exception {
    raiseHaltRow("an-earlier-pod");
    String id = freshId();
    produceTransfer(transfersExchange, id, transferJson(id));

    context = start("replica-late");

    ProjectionHalt halt = context.getBean(ProjectionHalt.class);
    await().atMost(Duration.ofSeconds(20)).until(halt::isHalted);
    assertThat(halt.reason().orElseThrow()).contains("an-earlier-pod");
    assertThat(context.getBean(ProjectionHaltHealthIndicator.class).health().getStatus())
        .isEqualTo(Status.DOWN);
    // The delivery waits in its queue: not consumed, not acknowledged, not lost.
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(TRANSFERS_SEEN).doesNotContain(id);
              assertThat(containersRunning(context)).isFalse();
            });
  }

  @Test
  void aClearedHaltLetsANewReplicaConsume() throws Exception {
    raiseHaltRow("an-earlier-pod");
    try (java.sql.Connection c = postgresConnection();
        java.sql.PreparedStatement clear =
            c.prepareStatement(
                "UPDATE workflow.projection_halt SET cleared_at = now(), cleared_by = 'operator',"
                    + " clear_note = 'fixed the decoder' WHERE projection_name = ?"
                    + " AND cleared_at IS NULL")) {
      clear.setString(1, haltName);
      clear.executeUpdate();
    }

    context = start("replica-after-clear");

    String id = freshId();
    produceTransfer(transfersExchange, id, transferJson(id));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).contains(id));
    assertThat(context.getBean(ProjectionHalt.class).isHalted()).isFalse();
  }

  @Test
  void aHaltRaisedWhileRunningStopsAReplicaThatPolls() throws Exception {
    context = start("replica-running");
    String before = freshId();
    produceTransfer(transfersExchange, before, transferJson(before));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).contains(before));

    raiseHaltRow("some-other-pod");

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> context.getBean(ProjectionHalt.class).isHalted());
    await().atMost(Duration.ofSeconds(20)).until(() -> !containersRunning(context));
    String after = freshId();
    produceTransfer(transfersExchange, after, transferJson(after));
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(TRANSFERS_SEEN).doesNotContain(after));
  }

  // -------------------------------------------------------------------------------------------
  // ADR-0035: a backlog is visible in this service's own metrics and logs
  // -------------------------------------------------------------------------------------------

  /**
   * 20 October's e2e run drained a 200,000-row backlog and logged one line. With the consumers
   * slowed and a batch smaller than the queue, the summary reports the queue's ready depth while it
   * drains and what was consumed, and the counters add up to exactly what was published.
   */
  @Test
  void theSummaryShowsTheBacklogWhileItDrainsAndTheCountersAddUp() {
    ENTRY_DELAY = Duration.ofMillis(100);
    context =
        start(
            "listener-test-pod",
            Map.of("PROJECTION_BATCH_SIZE", "5", "PROJECTION_ACTIVITY_LOG_INTERVAL", "1h"));
    ProjectionActivity activity = context.getBean(ProjectionActivity.class);
    ListAppender<ILoggingEvent> logs = captureActivityLogs();
    try {
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        String id = freshId();
        ids.add(id);
        produceTransfer(transfersExchange, id, transferJson(id));
      }

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                activity.logSummary();
                assertThat(lastSummary(logs)).containsPattern(transfersQueue + "=[1-9]");
              });
      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(() -> assertThat(TRANSFERS_SEEN).containsAll(ids));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(messagesCounted(context, transfersQueue)).isEqualTo(100));
      activity.logSummary();
      assertThat(lastSummary(logs))
          .contains("consumed [" + transfersQueue + "=", "ready []", "not draining []");
    } finally {
      releaseActivityLogs(logs);
    }
  }

  /**
   * The owner of every entries queue reports owning it, the standby reports owning none, and when
   * the owner stops, the hand-over is one INFO line on each side rather than nothing. Captured from
   * stdout: each context start resets logback, which detaches any appender a test added.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void queueOwnershipIsAGaugeAndEveryHandOverIsLogged(CapturedOutput output) throws Exception {
    String shard = entriesQueuePrefix + ".0";
    String acquired = "acquired entries queue " + shard + ":";
    context = start("replica-a");
    await().atMost(Duration.ofSeconds(20)).until(() -> ownedQueues(context) == 4);
    otherReplica = start("replica-b");
    await().atMost(Duration.ofSeconds(20)).until(() -> containersRunning(otherReplica));
    await()
        .during(Duration.ofSeconds(6))
        .atMost(Duration.ofSeconds(10))
        .until(() -> ownedQueues(otherReplica) == 0);
    assertThat(occurrences(output.getOut(), acquired))
        .as("the standby's refusals every recovery interval log nothing")
        .isEqualTo(1);

    context.close();
    context = null;

    await().atMost(Duration.ofSeconds(30)).until(() -> ownedQueues(otherReplica) == 4);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(output.getOut()).contains("released entries queue " + shard + " (");
              assertThat(occurrences(output.getOut(), acquired)).isEqualTo(2);
            });
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  private int ownedQueues(ConfigurableApplicationContext replica) {
    MeterRegistry meters = replica.getBean(MeterRegistry.class);
    int owned = 0;
    for (int i = 0; i < 4; i++) {
      owned +=
          (int)
              meters
                  .get(ProjectionActivity.OWNED_GAUGE)
                  .tag("queue", entriesQueuePrefix + "." + i)
                  .gauge()
                  .value();
    }
    return owned;
  }

  private static double messagesCounted(ConfigurableApplicationContext replica, String queue) {
    return replica
        .getBean(MeterRegistry.class)
        .get(ProjectionActivity.MESSAGES_COUNTER)
        .tag("queue", queue)
        .counter()
        .count();
  }

  private static ListAppender<ILoggingEvent> captureActivityLogs() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    activityLogger().addAppender(appender);
    return appender;
  }

  private static void releaseActivityLogs(ListAppender<ILoggingEvent> appender) {
    activityLogger().detachAppender(appender);
  }

  private static ch.qos.logback.classic.Logger activityLogger() {
    return (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(ProjectionActivity.class);
  }

  private static List<String> infoLines(ListAppender<ILoggingEvent> appender) {
    // AppenderBase.doAppend holds the appender's own lock while it adds to the list.
    synchronized (appender) {
      return appender.list.stream()
          .filter(e -> e.getLevel() == Level.INFO)
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
    }
  }

  private static String lastSummary(ListAppender<ILoggingEvent> appender) {
    List<String> summaries =
        infoLines(appender).stream().filter(l -> l.startsWith("projection activity")).toList();
    return summaries.isEmpty() ? "" : summaries.get(summaries.size() - 1);
  }

  // -------------------------------------------------------------------------------------------
  // infrastructure
  // -------------------------------------------------------------------------------------------

  private ConfigurableApplicationContext start() {
    return start("listener-test-pod");
  }

  private ConfigurableApplicationContext start(String instanceId) {
    return start(instanceId, Map.of());
  }

  private ConfigurableApplicationContext start(String instanceId, Map<String, Object> overrides) {
    PostgresFixture pg = PostgresFixture.getInstance();
    Map<String, Object> props = new java.util.HashMap<>();
    props.put("PROJECTION_AMQP_HOST", rabbit.getHost());
    props.put("PROJECTION_AMQP_PORT", rabbit.getPort());
    props.put("PROJECTION_AMQP_USERNAME", rabbit.getUsername());
    props.put("PROJECTION_AMQP_PASSWORD", rabbit.getPassword());
    props.put("PROJECTION_TRANSFERS_EXCHANGE", transfersExchange);
    props.put("PROJECTION_ENTRIES_EXCHANGE", entriesExchange);
    props.put("PROJECTION_DEAD_LETTER_QUEUE", deadLetterQueue);
    props.put("PROJECTION_TRANSFERS_QUEUE", transfersQueue);
    props.put("PROJECTION_ENTRIES_QUEUE_PREFIX", entriesQueuePrefix);
    props.put("PROJECTION_ENTRIES_QUEUE_COUNT", "4");
    props.put("POSTGRES_URL", pg.getJdbcUrl());
    props.put("POSTGRES_USER", pg.getUsername());
    props.put("POSTGRES_PASSWORD", pg.getPassword());
    props.put("PROJECTION_RETRY_INITIAL_BACKOFF", "100ms");
    props.put("PROJECTION_RETRY_MAX_BACKOFF", "500ms");
    props.put("PROJECTION_PREFETCH", "10");
    props.put("PROJECTION_INSTANCE_ID", instanceId);
    props.put("PROJECTION_HALT_POLL_INTERVAL", "200ms");
    props.put("fondaria.projection.halt.projection-name", haltName);
    props.put("server.port", "0");
    props.put("spring.main.banner-mode", "off");
    props.putAll(overrides);
    return new SpringApplicationBuilder(ProjectionWorkerApplication.class, TestSinkConfig.class)
        .properties(props)
        .run();
  }

  private static boolean containersRunning(ConfigurableApplicationContext replica) {
    return replica.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers().stream()
        .anyMatch(MessageListenerContainer::isRunning);
  }

  private static java.sql.Connection postgresConnection() throws java.sql.SQLException {
    PostgresFixture pg = PostgresFixture.getInstance();
    return java.sql.DriverManager.getConnection(
        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private void raiseHaltRow(String haltedBy) throws java.sql.SQLException {
    try (java.sql.Connection c = postgresConnection();
        java.sql.PreparedStatement insert =
            c.prepareStatement(
                "INSERT INTO workflow.projection_halt"
                    + " (projection_name, halted_by, reason_code, source_queue)"
                    + " VALUES (?, ?, 'MALFORMED_EVENT', 'test.queue')")) {
      insert.setString(1, haltName);
      insert.setString(2, haltedBy);
      insert.executeUpdate();
    }
  }

  private List<Map<String, Object>> haltRows() throws java.sql.SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (java.sql.Connection c = postgresConnection();
        java.sql.PreparedStatement select =
            c.prepareStatement(
                "SELECT halted_by, reason_code, source_queue, cleared_at"
                    + " FROM workflow.projection_halt WHERE projection_name = ?")) {
      select.setString(1, haltName);
      try (java.sql.ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new java.util.HashMap<>();
          row.put("halted_by", rs.getString("halted_by"));
          row.put("reason_code", rs.getString("reason_code"));
          row.put("source_queue", rs.getString("source_queue"));
          row.put("cleared_at", rs.getTimestamp("cleared_at"));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private long poisonCount() {
    return (long)
        context
            .getBean(MeterRegistry.class)
            .get(LedgerEventListener.POISON_COUNTER)
            .counter()
            .count();
  }

  private boolean dlqHasRecord() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      long deadline = System.currentTimeMillis() + 15_000;
      while (System.currentTimeMillis() < deadline) {
        GetResponse response = channel.basicGet(deadLetterQueue, true);
        if (response != null) {
          String body = new String(response.getBody(), StandardCharsets.UTF_8);
          if (body.contains("MALFORMED_EVENT")) {
            return true;
          }
        }
        Thread.sleep(200);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return false;
  }

  private void produceTransfer(String exchange, String key, String value) {
    produceRaw(exchange, key, value);
  }

  private void produceEntry(String exchange, String key, String value) {
    produceRaw(exchange, key, value);
  }

  private void produceRaw(String exchange, String key, String value) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.basicPublish(
          exchange,
          key,
          MessageProperties.PERSISTENT_TEXT_PLAIN,
          value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String freshId() {
    return Long.toString(
        Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L) + 1);
  }

  private static String transferJson(String transferId) {
    return "{"
        + "\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":484,"
        + "\"timestamp\":\"1700000000000000000\",\"transferId\":\""
        + transferId
        + "\",\"amount\":\"125000\",\"pendingId\":\"0\",\"transferCode\":1,\"transferFlags\":0,"
        + "\"transferTimestamp\":\"1700000000000000000\",\"userData128\":\"0\",\"userData64\":\"0\","
        + "\"userData32\":0,\"debitAccountId\":\"700000000000000010\","
        + "\"creditAccountId\":\"700000000000000020\",\"debitAccountCode\":1,\"creditAccountCode\":2}";
  }

  private static String entryJson(String accountId) {
    return "{"
        + "\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":484,"
        + "\"timestamp\":\"1700000000000000000\",\"transferId\":\"900000000000000001\","
        + "\"transferCode\":1,\"transferTimestamp\":\"1700000000000000000\",\"amount\":\"125000\","
        + "\"direction\":\"DEBIT\",\"accountId\":\""
        + accountId
        + "\",\"accountCode\":1,\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":\"125000\","
        + "\"creditsPending\":\"0\",\"creditsPosted\":\"0\",\"accountUserData128\":\"0\","
        + "\"accountUserData64\":\"0\",\"accountUserData32\":0,\"pendingId\":\"0\"}";
  }

  @TestConfiguration
  static class TestSinkConfig {

    @Bean
    @Primary
    ProjectionSink programmableSink() {
      return new ProjectionSink() {
        @Override
        public void accept(LedgerTransferEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
          run(
              () -> {
                CONSUMER_BY_TRANSFER.put(event.transferId(), consumer);
                TRANSFERS_SEEN.add(event.transferId());
              });
        }

        @Override
        public void accept(LedgerEntryEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
          run(
              () -> {
                ENTRY_CONSUMER_IDS.add(consumer.id());
                ACCOUNTS_SEEN.add(event.accountId());
              });
        }

        @Override
        public void acceptBatch(List<Delivery> deliveries) {
          BATCH_SIZES.add(deliveries.size());
          ProjectionSink.super.acceptBatch(deliveries);
        }

        private void run(Runnable onSuccess) {
          if (FAILURES_REMAINING.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new RuntimeException("simulated transient projection failure");
          }
          int now = IN_FLIGHT.incrementAndGet();
          MAX_IN_FLIGHT.accumulateAndGet(now, Math::max);
          try {
            if (!ENTRY_DELAY.isZero()) {
              Thread.sleep(ENTRY_DELAY.toMillis());
            }
            onSuccess.run();
            ACCEPTED_OK.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            IN_FLIGHT.decrementAndGet();
          }
        }
      };
    }
  }
}
