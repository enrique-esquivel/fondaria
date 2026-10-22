package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Task 11.2's two acceptance criteria that need a real request through a real Spring context rather
 * than a unit test: no span attribute carries PII or an amount, and a trace id correlates into the
 * service's own logs.
 *
 * <p><b>{@link InMemorySpanExporter} replaces the real OTLP exporter for this context only</b> —
 * {@link TestConfig} supplies it as the {@code SpanExporter} bean, which Spring Boot's own OTLP
 * autoconfiguration backs off for ({@code @ConditionalOnMissingBean}). Every span this service
 * creates during the test, including the ones {@code TigerBeetleLedgerAdapter} wraps around the
 * ledger call, ends up here — asserted over the exporter, literally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private InMemorySpanExporter spanExporter;
  @Autowired private SdkTracerProvider tracerProvider;

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogsAndSpans() {
    spanExporter.reset();
    logs = new ListAppender<>();
    logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    logs.start();
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void release() {
    rootLogger().detachAppender(logs);
  }

  @Test
  @DisplayName("no span attribute from a full cash-receipt request carries PII or an amount")
  void noSpanAttributeCarriesPiiOrAnAmount() {
    OrganizationId organizationId = registerOrganization();
    long amountCents = 175_000L;
    String bankReference = "SPEI-2026-08-18-" + UUID.randomUUID();
    String operatorId = "ops-" + UUID.randomUUID();

    post(organizationId, "key-" + UUID.randomUUID(), amountCents, bankReference, operatorId);
    flushSpans();

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertThat(spans).as("the request must have produced at least one span").isNotEmpty();
    for (SpanData span : spans) {
      span.getAttributes()
          .forEach(
              (key, value) -> {
                String rendered = String.valueOf(value);
                assertThat(rendered)
                    .as("span %s attribute %s", span.getName(), key)
                    .doesNotContain(String.valueOf(amountCents))
                    .doesNotContain(bankReference)
                    .doesNotContain(operatorId);
              });
    }
  }

  @Test
  @DisplayName("a cash-receipt request's trace id correlates into this service's own logs")
  void traceIdCorrelatesIntoLogs() {
    OrganizationId organizationId = registerOrganization();

    post(
        organizationId,
        "key-" + UUID.randomUUID(),
        175_000L,
        "SPEI-2026-08-18-" + UUID.randomUUID(),
        "ops-" + UUID.randomUUID());
    flushSpans();

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertThat(spans).isNotEmpty();
    String traceId = spans.get(0).getTraceId();
    assertThat(logs.list)
        .as("at least one log line during the request must carry the same trace id")
        .anySatisfy(
            event -> assertThat(event.getMDCPropertyMap()).containsEntry("traceId", traceId));
  }

  /**
   * The real OTel span processor is a {@code BatchSpanProcessor} (Spring Boot's own default), which
   * exports on a timer rather than synchronously on span end. A test asserting immediately after a
   * request would otherwise be racing that timer.
   */
  private void flushSpans() {
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
  }

  private ResponseEntity<Map<String, Object>> post(
      OrganizationId organizationId,
      String idempotencyKey,
      long cents,
      String bankReference,
      String operatorId) {
    return http.exchange(
        "/v1/organizations/" + organizationId + "/cash-receipts",
        HttpMethod.POST,
        json(
            "{\"amountMinorUnits\":"
                + cents
                + ",\"currency\":\"MXN\",\"valueDate\":\"2026-08-18\","
                + "\"bankReference\":\""
                + bankReference
                + "\",\"operatorId\":\""
                + operatorId
                + "\"}",
            idempotencyKey),
        new ParameterizedTypeReference<>() {});
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

  private static ch.qos.logback.classic.Logger rootLogger() {
    return (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }
  }
}
