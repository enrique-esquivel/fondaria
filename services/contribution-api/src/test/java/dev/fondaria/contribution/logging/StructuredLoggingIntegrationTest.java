package dev.fondaria.contribution.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.logging.LogRedactor;
import dev.fondaria.common.logging.RedactingConsoleAppender;
import dev.fondaria.contribution.organization.web.RegisterOrganizationRequest;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.endpoint.loggers.enabled=true",
      "management.endpoints.web.exposure.include=health,loggers"
    })
class StructuredLoggingIntegrationTest extends ServiceIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private TestRestTemplate http;
  @Autowired private LoggingSystem loggingSystem;

  private Logger rootLogger;
  private RedactingConsoleAppender capturingAppender;
  private ByteArrayOutputStream outputStream;

  @BeforeEach
  void setUp() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

    outputStream = new ByteArrayOutputStream();
    capturingAppender = new RedactingConsoleAppender();
    capturingAppender.setContext(context);
    capturingAppender.setService("contribution-api");
    capturingAppender.setOutputStream(outputStream);
    capturingAppender.start();

    rootLogger.addAppender(capturingAppender);
  }

  @AfterEach
  void tearDown() {
    if (rootLogger != null && capturingAppender != null) {
      rootLogger.detachAppender(capturingAppender);
      capturingAppender.stop();
    }
  }

  @Test
  @DisplayName(
      "appender redacts PAN, IBAN, email, token, and idempotencyKey under live Spring context")
  void redactionAtAppenderBoundaryUnderLiveContext() throws Exception {
    org.slf4j.Logger logger = LoggerFactory.getLogger(StructuredLoggingIntegrationTest.class);

    String pan = "4111222233334444";
    String iban = "MX1234567890123456789012";
    String email = "employee@fondaria.example.mx";
    String token = "secret-access-token-999";
    String idempotencyKey = "idem-tx-uuid-8888";

    logger.info(
        "Processing transaction with pan={} iban={} email={} token={} idempotencyKey={}",
        pan,
        iban,
        email,
        token,
        idempotencyKey);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(output).isNotBlank();

    // Verify raw values never appear in output bytes
    assertThat(output)
        .doesNotContain(pan)
        .doesNotContain(iban)
        .doesNotContain(email)
        .doesNotContain(token)
        .doesNotContain(idempotencyKey);

    // Verify it parses as JSON and contains REDACTED
    JsonNode json = objectMapper.readTree(output.trim());
    assertThat(json.get("message").asText()).contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("every log line parses as JSON with docs/ENGINEERING-RULES.md §7 fields")
  void everyLogLineParsesAsJson() throws Exception {
    org.slf4j.Logger logger =
        LoggerFactory.getLogger("dev.fondaria.contribution.test.JsonValidation");

    logger.info("Simple info message");
    logger.warn("Warning with detail={}", "something unusual");
    logger.error("Error with exception", new RuntimeException("Database timeout error"));

    String[] lines = outputStream.toString(StandardCharsets.UTF_8).split("\\R");
    assertThat(lines).isNotEmpty();

    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode node = objectMapper.readTree(line);
      assertThat(node.has("timestamp")).as("Must have timestamp: %s", line).isTrue();
      assertThat(node.has("level")).as("Must have level: %s", line).isTrue();
      assertThat(node.has("service")).as("Must have service: %s", line).isTrue();
      assertThat(node.has("traceId")).as("Must have traceId: %s", line).isTrue();
      assertThat(node.has("message")).as("Must have message: %s", line).isTrue();
    }
  }

  @Test
  @DisplayName("log volume per transaction is bounded and measured")
  void logVolumePerTransactionIsBoundedAndMeasured() throws Exception {
    // Clear setup noise
    outputStream.reset();

    String rawRfc = freshRfc();
    String idempotencyKey = "key-" + UUID.randomUUID();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);

    RegisterOrganizationRequest body =
        new RegisterOrganizationRequest(
            "MX_RFC", rawRfc, "Acme Test S.A.", "CTR-" + UUID.randomUUID(), "STANDARD");

    ResponseEntity<String> response =
        http.exchange(
            "/v1/organizations",
            org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    String[] lines = output.split("\\R");

    List<String> validJsonLines = new ArrayList<>();
    long totalBytes = 0;

    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode node = objectMapper.readTree(line);
      assertThat(node.has("timestamp")).isTrue();
      validJsonLines.add(line);
      totalBytes += line.getBytes(StandardCharsets.UTF_8).length;
    }

    // Bounded log count: transaction must not generate spammy unbounded log volume
    assertThat(validJsonLines.size())
        .as("Log lines per transaction must be bounded")
        .isBetween(1, 25);

    // Bounded and measured log volume: must measure total size and keep under threshold
    assertThat(totalBytes)
        .as("Log volume per transaction must be bounded (< 15 KB)")
        .isLessThan(15_360);

    // Prohibited items must not leak
    assertThat(output).as("RFC must never leak in logs").doesNotContain(rawRfc);
    assertThat(output).as("Idempotency key must never leak in logs").doesNotContain(idempotencyKey);
  }

  @Test
  @DisplayName("log level is configurable per package at runtime without a restart")
  void logLevelConfigurablePerPackageAtRuntimeWithoutRestart() {
    org.slf4j.Logger logger =
        LoggerFactory.getLogger("dev.fondaria.contribution.runtime.LogLevelTest");

    // 1. Initial level is INFO (rootLogger default), so DEBUG logs are suppressed
    outputStream.reset();
    logger.debug("debug-marker-suppressed-1");
    assertThat(outputStream.toString(StandardCharsets.UTF_8))
        .doesNotContain("debug-marker-suppressed-1");

    // The level is JVM-wide and this context is cached: put back exactly what was configured
    // (nothing, here — the package inherits), even if an assertion below fails.
    LoggerConfiguration configured =
        loggingSystem.getLoggerConfiguration("dev.fondaria.contribution.runtime");
    LogLevel original = configured == null ? null : configured.getConfiguredLevel();
    try {
      // 2. Change package level to DEBUG at runtime via LoggingSystem
      assertThat(loggingSystem).isNotNull();
      loggingSystem.setLogLevel("dev.fondaria.contribution.runtime", LogLevel.DEBUG);

      // 3. Now DEBUG logs are emitted without restarting the service
      outputStream.reset();
      logger.debug("debug-marker-visible-2");
      assertThat(outputStream.toString(StandardCharsets.UTF_8)).contains("debug-marker-visible-2");

      // 4. Change package level to WARN at runtime
      loggingSystem.setLogLevel("dev.fondaria.contribution.runtime", LogLevel.WARN);

      // 5. Now INFO and DEBUG logs are suppressed, but WARN is emitted
      outputStream.reset();
      logger.info("info-marker-suppressed-3");
      logger.warn("warn-marker-visible-4");
      String warnOutput = outputStream.toString(StandardCharsets.UTF_8);
      assertThat(warnOutput).doesNotContain("info-marker-suppressed-3");
      assertThat(warnOutput).contains("warn-marker-visible-4");
    } finally {
      loggingSystem.setLogLevel("dev.fondaria.contribution.runtime", original);
    }
  }

  private static String freshRfc() {
    String randomLetters =
        UUID.randomUUID().toString().replaceAll("[^a-zA-Z]", "").substring(0, 3).toUpperCase();
    return randomLetters + "850101XYZ";
  }
}
