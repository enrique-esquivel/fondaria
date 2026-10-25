package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.fondaria.projection.config.ActivityProperties;
import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.projection.config.ProjectionConsumerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStoppedEvent;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerTerminatedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;

/**
 * {@link ProjectionActivity}'s metrics and log lines (ADR-0035), with the broker's depths and the
 * consumer events faked.
 */
class ProjectionActivityTest {

  private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
  private static final String TRANSFERS = "projection.transfers.v1";
  private static final String ENTRIES_3 = "projection.entries.v1.3";
  private static final String ENTRIES_7 = "projection.entries.v1.7";

  private final MutableClock clock = new MutableClock(T0);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ProjectionHalt halt = new ProjectionHalt();
  private final Map<String, Long> brokerDepths = new HashMap<>();
  private final CdcLagMetrics lag =
      new CdcLagMetrics(
          registry,
          clock,
          new CheckpointProperties("pod", Duration.ofMinutes(5), Duration.ofHours(1)));
  private final ProjectionActivity activity =
      new ProjectionActivity(
          registry,
          new ProjectionConsumerProperties(
              "rabbitmq", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null),
          new ActivityProperties(null, null),
          lag,
          halt,
          queues -> Map.copyOf(brokerDepths),
          mock(TaskScheduler.class),
          clock);

  private final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProjectionActivity.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  // --- counters ------------------------------------------------------------------------------

  @Test
  void everyConfiguredQueueStartsAtZeroSoRatesExistBeforeTheFirstBatch() {
    assertThat(messages(TRANSFERS)).isZero();
    assertThat(messages(ENTRIES_3)).isZero();
    assertThat(registry.find(ProjectionActivity.MESSAGES_COUNTER).counters()).hasSize(17);
  }

  @Test
  void aCommittedBatchCountsOneBatchAndItsMessagesOnItsQueue() {
    activity.batchCommitted(TRANSFERS, 200);
    activity.batchCommitted(TRANSFERS, 37);
    activity.batchCommitted(ENTRIES_3, 5);

    assertThat(batches(TRANSFERS)).isEqualTo(2.0);
    assertThat(messages(TRANSFERS)).isEqualTo(237.0);
    assertThat(batches(ENTRIES_3)).isEqualTo(1.0);
    assertThat(messages(ENTRIES_3)).isEqualTo(5.0);
    assertThat(messages(ENTRIES_7)).isZero();
  }

  @Test
  void theCountersAreCumulativeAcrossSummaries() {
    activity.batchCommitted(TRANSFERS, 10);
    activity.logSummary();
    activity.batchCommitted(TRANSFERS, 10);

    assertThat(messages(TRANSFERS)).isEqualTo(20.0);
  }

  // --- ownership -----------------------------------------------------------------------------

  @Test
  void consumeOkOnAnEntriesQueueMarksItOwnedAndLogsOnce() {
    SimpleMessageListenerContainer container = containerOn(ENTRIES_3);

    activity.onConsumeOk(new ConsumeOkEvent(container, ENTRIES_3, "amq.ctag-1"));
    activity.onConsumeOk(new ConsumeOkEvent(container, ENTRIES_3, "amq.ctag-1"));

    assertThat(owned(ENTRIES_3)).isEqualTo(1.0);
    assertThat(owned(ENTRIES_7)).isZero();
    assertThat(infoLines())
        .containsExactly(
            "acquired entries queue projection.entries.v1.3: this pod is its exclusive consumer");
  }

  @Test
  void aFailedConsumerReleasesItsQueueNamingOnlyTheExceptionType() {
    SimpleMessageListenerContainer container = containerOn(ENTRIES_3);
    activity.onConsumeOk(new ConsumeOkEvent(container, ENTRIES_3, "amq.ctag-1"));

    activity.onConsumerFailed(
        new ListenerContainerConsumerFailedEvent(
            container,
            "Consumer raised exception",
            new IllegalStateException("connection to broker-7.internal:5672 lost"),
            false));

    assertThat(owned(ENTRIES_3)).isZero();
    assertThat(infoLines())
        .last()
        .isEqualTo(
            "released entries queue projection.entries.v1.3 (IllegalStateException):"
                + " another pod may take it over");
    assertThat(String.join("\n", infoLines())).doesNotContain("broker-7");
  }

  @Test
  void aStoppedOrTerminatedConsumerReleasesItsQueue() {
    SimpleMessageListenerContainer three = containerOn(ENTRIES_3);
    SimpleMessageListenerContainer seven = containerOn(ENTRIES_7);
    activity.onConsumeOk(new ConsumeOkEvent(three, ENTRIES_3, "amq.ctag-1"));
    activity.onConsumeOk(new ConsumeOkEvent(seven, ENTRIES_7, "amq.ctag-2"));

    activity.onConsumerStopped(new AsyncConsumerStoppedEvent(three, new Object()));
    activity.onConsumerTerminated(new ListenerContainerConsumerTerminatedEvent(seven, "stopped"));

    assertThat(owned(ENTRIES_3)).isZero();
    assertThat(owned(ENTRIES_7)).isZero();
  }

  @Test
  void shutdownReleasesEveryOwnedQueueOnce() {
    activity.onConsumeOk(new ConsumeOkEvent(containerOn(ENTRIES_3), ENTRIES_3, "amq.ctag-1"));

    activity.stop();
    activity.onConsumerStopped(new AsyncConsumerStoppedEvent(containerOn(ENTRIES_3), new Object()));

    assertThat(owned(ENTRIES_3)).isZero();
    assertThat(infoLines())
        .filteredOn(line -> line.startsWith("released"))
        .containsExactly(
            "released entries queue projection.entries.v1.3 (shutting down):"
                + " another pod may take it over");
  }

  @Test
  void aStandbysRepeatedRefusalsLogNothing() {
    SimpleMessageListenerContainer container = containerOn(ENTRIES_3);

    for (int i = 0; i < 5; i++) {
      activity.onConsumerFailed(
          new ListenerContainerConsumerFailedEvent(
              container, "exclusive", new RuntimeException("in exclusive use"), false));
    }

    assertThat(owned(ENTRIES_3)).isZero();
    assertThat(infoLines()).isEmpty();
  }

  @Test
  void theTransfersQueueIsNotAnOwnershipQueue() {
    activity.onConsumeOk(new ConsumeOkEvent(containerOn(TRANSFERS), TRANSFERS, "amq.ctag-9"));

    assertThat(registry.find(ProjectionActivity.OWNED_GAUGE).tag("queue", TRANSFERS).gauge())
        .isNull();
    assertThat(infoLines()).isEmpty();
  }

  // --- the summary line ----------------------------------------------------------------------

  @Test
  void aStalledBacklogShowsAsReadyDepthAndNotDraining() {
    // 20 October's e2e: 200,000 rows queued, this pod owns the shard and commits nothing.
    activity.onConsumeOk(new ConsumeOkEvent(containerOn(ENTRIES_3), ENTRIES_3, "amq.ctag-1"));
    brokerDepths.put(TRANSFERS, 180_000L);
    brokerDepths.put(ENTRIES_3, 400_000L);
    brokerDepths.put(ENTRIES_7, 0L);
    lag.lastTimestampAdvancedTo("pod/a", nanos(T0.minusSeconds(90)));
    clock.advance(Duration.ofSeconds(60));

    activity.logSummary();

    assertThat(infoLines())
        .last()
        .isEqualTo(
            "projection activity over 60s: 0 batches, 0 messages; entries queues owned 1/16;"
                + " cdc lag 150.0s; consumed []; ready [projection.transfers.v1=180000,"
                + " projection.entries.v1.3=400000]; not draining [projection.transfers.v1,"
                + " projection.entries.v1.3]");
  }

  @Test
  void aQueueOwnedByAnotherPodIsBacklogButNotThisPodsStall() {
    brokerDepths.put(ENTRIES_7, 50L);

    activity.logSummary();

    assertThat(infoLines().get(infoLines().size() - 1))
        .contains("ready [projection.entries.v1.7=50]", "not draining []");
  }

  @Test
  void aDrainingPodReportsWhatItConsumedPerQueueAndResetsTheWindow() {
    activity.onConsumeOk(new ConsumeOkEvent(containerOn(ENTRIES_3), ENTRIES_3, "amq.ctag-1"));
    activity.batchCommitted(TRANSFERS, 200);
    activity.batchCommitted(TRANSFERS, 200);
    activity.batchCommitted(ENTRIES_3, 150);
    brokerDepths.put(ENTRIES_3, 1_000L);
    clock.advance(Duration.ofSeconds(60));

    activity.logSummary();
    clock.advance(Duration.ofSeconds(60));
    activity.logSummary();

    List<String> lines = infoLines();
    assertThat(lines.get(lines.size() - 2))
        .startsWith("projection activity over 60s: 3 batches, 550 messages;")
        .contains(
            "consumed [projection.transfers.v1=400, projection.entries.v1.3=150]",
            "ready [projection.entries.v1.3=1000]",
            "not draining []");
    assertThat(lines.get(lines.size() - 1))
        .startsWith("projection activity over 60s: 0 batches, 0 messages;")
        .contains("not draining [projection.entries.v1.3]");
  }

  @Test
  void depthsTheBrokerCouldNotGiveAreReportedUnavailableNotZero() {
    ProjectionActivity blind =
        new ProjectionActivity(
            registry,
            new ProjectionConsumerProperties(
                "rabbitmq", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null),
            new ActivityProperties(null, null),
            lag,
            halt,
            queues -> Map.of(),
            mock(TaskScheduler.class),
            clock);

    blind.logSummary();

    assertThat(infoLines().get(infoLines().size() - 1)).contains("ready [unavailable]");
  }

  @Test
  void aHaltedPodSaysSo() {
    halt.trigger("poison");

    activity.logSummary();

    assertThat(infoLines().get(infoLines().size() - 1)).contains("0 messages (HALTED);");
  }

  @Test
  void theSummaryNeverNamesATransferAnAccountOrAnAmount() {
    activity.batchCommitted(TRANSFERS, 3);
    brokerDepths.put(TRANSFERS, 9L);

    activity.logSummary();

    // Only queue names, counts and the lag: the summary has no access to message contents.
    assertThat(infoLines().get(infoLines().size() - 1)).matches("[a-z0-9 .,;:=()\\[\\]/]+");
  }

  // --- helpers -------------------------------------------------------------------------------

  private List<String> infoLines() {
    return logs.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private double batches(String queue) {
    return registry.get(ProjectionActivity.BATCHES_COUNTER).tag("queue", queue).counter().count();
  }

  private double messages(String queue) {
    return registry.get(ProjectionActivity.MESSAGES_COUNTER).tag("queue", queue).counter().count();
  }

  private double owned(String queue) {
    return registry.get(ProjectionActivity.OWNED_GAUGE).tag("queue", queue).gauge().value();
  }

  private static SimpleMessageListenerContainer containerOn(String queue) {
    SimpleMessageListenerContainer container =
        new SimpleMessageListenerContainer(mock(ConnectionFactory.class));
    container.setQueueNames(queue);
    return container;
  }

  private static String nanos(Instant instant) {
    return Long.toString(instant.getEpochSecond() * 1_000_000_000L);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
