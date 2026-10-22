package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import dev.fondaria.testing.RabbitMqFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole of Task 7.5a end to end against real infrastructure (ADR-0022): the three upstream runs
 * pass for a business date, {@link SignoffService} writes {@code workflow.close_signoff} and
 * produces {@code close.signed-off.v1}, and the row is marked announced only after the record is
 * confirmed.
 */
@SpringBootTest
class SignoffPublicationIntegrationTest extends ServiceIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private SignoffService service;
  @Autowired private SignoffRepository signoffs;
  @Autowired private JdbcClient jdbc;
  @Autowired private dev.fondaria.common.BusinessCalendar calendar;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("gates open: the row is written, the event is confirmed, announced_at is then set")
  void signsOffAndAnnounces() throws Exception {
    CloseRunFixtures runs = new CloseRunFixtures(jdbc);
    LocalDate businessDate = randomPastBusinessDate();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

    runs.passedInternalRun(businessDate, at);
    runs.passedCustodianRun(businessDate, at);
    long positionCount = ThreadLocalRandom.current().nextLong(1_000, 9_999);
    runs.completedSnapshotRun(businessDate, at, positionCount);

    RabbitMqFixture rabbit = RabbitMqFixture.getInstance();
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    String testQueue = "test.signoff." + UUID.randomUUID();

    try (Connection conn = factory.newConnection();
        Channel ch = conn.createChannel()) {
      ch.exchangeDeclare(CloseSignoffTopic.NAME, "fanout", true);
      ch.queueDeclare(testQueue, false, true, true, null);
      ch.queueBind(testQueue, CloseSignoffTopic.NAME, "");

      SignoffResult result = service.signOffFor(businessDate);

      assertThat(result.newlySignedOff()).isTrue();
      assertThat(result.announced()).isTrue();

      CloseSignoff row = signoffs.find(businessDate).orElseThrow();
      assertThat(row.isAnnounced()).isTrue();
      assertThat(row.snapshotPositionCount()).isEqualTo(positionCount);

      boolean expectedMonthEndFlag =
          !java.time.YearMonth.from(calendar.nextBusinessDate(businessDate))
              .equals(java.time.YearMonth.from(businessDate));

      JsonNode event = consumeEventFor(ch, testQueue, businessDate);
      assertThat(event.get("schemaVersion").asText()).isEqualTo("v1");
      assertThat(event.get("businessDate").asText()).isEqualTo(businessDate.toString());
      assertThat(event.get("snapshotPositionCount").asLong()).isEqualTo(positionCount);
      assertThat(event.get("snapshotObjectKey").asText()).isEqualTo(row.snapshotObjectKey());
      assertThat(event.get("reconciliationRunId").asText())
          .isEqualTo(row.reconciliationRunId().toString());
      assertThat(event.get("lastBusinessDayOfMonth").asBoolean())
          .as("the flag must match the calendar's own answer for %s", businessDate)
          .isEqualTo(expectedMonthEndFlag);
      assertThat(
              meterRegistry
                  .get("rabbitmq_publish_duration_seconds")
                  .tag("destination", CloseSignoffTopic.NAME)
                  .timer()
                  .count())
          .isPositive();
    }
  }

  private LocalDate randomPastBusinessDate() {
    LocalDate date =
        LocalDate.of(1000, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3_000_000));
    while (!calendar.isBusinessDate(date)) {
      date = date.plusDays(1);
    }
    return date;
  }

  private JsonNode consumeEventFor(Channel channel, String queue, LocalDate businessDate)
      throws Exception {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      GetResponse resp = channel.basicGet(queue, true);
      if (resp != null) {
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        JsonNode node = MAPPER.readTree(body);
        if (businessDate.toString().equals(node.get("businessDate").asText())) {
          return node;
        }
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException(
        "Did not receive close.signed-off.v1 for " + businessDate + " within deadline");
  }
}
