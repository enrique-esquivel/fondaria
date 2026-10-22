package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Task 11.2's first acceptance criterion, literally: <b>a single trace in Tempo spans HTTP ingress
 * → ledger → CDC → projection</b>.
 *
 * <p>A cash receipt is posted over real HTTP to the running {@code contribution-api} — not called
 * as a bean, which would have no HTTP-server span to start a trace from — and its movement travels
 * the real path {@link PlatformStack} raises: TigerBeetle, the stock {@code tigerbeetle amqp} job,
 * RabbitMQ, {@code amqp-redpanda-bridge}, Redpanda, {@code projection-worker}. Every service in
 * that path exports straight to the shared {@link dev.fondaria.testing.TempoFixture} (see {@code
 * PlatformStack#start}); the trace id is recovered from {@code contribution-api}'s own log line the
 * same way {@code TelemetryIntegrationTest} recovers it, and queried back out of Tempo.
 *
 * <p>This does not re-prove {@code TraceCorrelationPublisher} writes correctly or that {@code
 * TraceCorrelationReader} parses a well-formed entry — those are unit- and single-service-tested
 * elsewhere. What only a real, multi-service run proves is that the two agree end to end: the same
 * Redis, the same key format, a real CDC hop in between, and a real trace backend showing one trace
 * rather than two.
 */
@Tag("integration")
class CdcBoundaryTraceEndToEndTest {

  private static PlatformStack stack;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private ListAppender<ILoggingEvent> logs;

  @BeforeAll
  static void raiseStack() {
    stack = PlatformStack.getInstance();
  }

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    logs.start();
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    rootLogger().detachAppender(logs);
  }

  @Test
  @DisplayName("a cash receipt's trace spans contribution-api and projection-worker in Tempo")
  void traceSpansContributionApiAndProjectionWorker() throws Exception {
    OrganizationId organizationId = Scenario.registerOrganization(stack);
    // Registration is its own bean call with its own (irrelevant) trace — cleared so the only
    // log lines left are the cash receipt's, and traceIdFromLogs() cannot pick up the wrong one.
    logs.list.clear();

    HttpResponse<String> response = postCashReceipt(organizationId);
    assertThat(response.statusCode()).isEqualTo(201);

    String traceId = traceIdFromLogs();

    // A larger budget than Convergence.BUDGET (60s, calibrated for the CDC pipeline alone):
    // this also waits out Tempo's own ingester flush cycle (TempoFixture's trace_idle_period)
    // on top of the same ledger -> CDC -> projection hops.
    Convergence.awaitConvergence(
        "the trace reaches both contribution-api and projection-worker in Tempo",
        Duration.ofSeconds(60),
        () ->
            serviceNamesInTrace(traceId)
                .containsAll(Set.of("contribution-api", "projection-worker")));
  }

  private HttpResponse<String> postCashReceipt(OrganizationId organizationId) throws Exception {
    String body =
        "{\"amountMinorUnits\":175000,\"currency\":\"MXN\",\"valueDate\":\"2026-08-18\","
            + "\"bankReference\":\"SPEI-"
            + UUID.randomUUID()
            + "\",\"operatorId\":\"ops-e2e\"}";
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    stack.contributionApiBaseUrl()
                        + "/v1/organizations/"
                        + organizationId
                        + "/cash-receipts"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .header("Authorization", PlatformStack.internalServiceAuthorization())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The same MDC technique {@code TelemetryIntegrationTest} uses, narrowed to {@code
   * CashReceiptService}'s own logger — this shared JVM runs seven services, and eod-job's scheduled
   * sweeps run on their own background thread throughout the suite, each with its own real but
   * unrelated trace id. Picking "the first log line with any trace id" is a genuine race with that
   * background noise; picking the log line the request itself is known to emit is not.
   */
  private String traceIdFromLogs() {
    for (ILoggingEvent event : logs.list) {
      if (!"dev.fondaria.contribution.cashreceipt.CashReceiptService"
          .equals(event.getLoggerName())) {
        continue;
      }
      String traceId = event.getMDCPropertyMap().get("traceId");
      if (traceId != null && !traceId.isBlank()) {
        return traceId;
      }
    }
    throw new AssertionError(
        "no CashReceiptService log line during the request carried a trace id");
  }

  /**
   * Tempo's {@code /api/traces/{id}} answers in OTLP's own JSON shape, not Jaeger's — each
   * resource's {@code service.name} is an OTLP {@code KeyValue}, {@code {"key": "service.name",
   * "value": {"stringValue": "..."}}}, never a bare {@code serviceName} field. This walks every
   * object for that exact key/value shape rather than a fixed path, so nesting depth doesn't
   * matter.
   */
  private Set<String> serviceNamesInTrace(String traceId) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      dev.fondaria.testing.TempoFixture.getInstance().getQueryApiUrl()
                          + "/api/traces/"
                          + traceId))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Set.of();
      }
      Set<String> serviceNames = new HashSet<>();
      collectServiceNames(MAPPER.readTree(response.body()), serviceNames);
      return serviceNames;
    } catch (Exception e) {
      return Set.of();
    }
  }

  private static void collectServiceNames(JsonNode node, Set<String> into) {
    if (node.isObject()) {
      JsonNode key = node.get("key");
      JsonNode value = node.get("value");
      if (key != null
          && "service.name".equals(key.asText())
          && value != null
          && value.has("stringValue")) {
        into.add(value.get("stringValue").asText());
      }
      node.fields().forEachRemaining(entry -> collectServiceNames(entry.getValue(), into));
    } else if (node.isArray()) {
      node.forEach(child -> collectServiceNames(child, into));
    }
  }

  private static ch.qos.logback.classic.Logger rootLogger() {
    return (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }
}
