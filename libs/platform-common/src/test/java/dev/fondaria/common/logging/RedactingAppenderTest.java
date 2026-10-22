package dev.fondaria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RedactingAppenderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private LoggerContext context;
  private Logger logger;
  private RedactingConsoleAppender appender;
  private ByteArrayOutputStream outputStream;

  @BeforeEach
  void setUp() {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();
    logger = context.getLogger("dev.fondaria.test.RedactingAppenderTest");

    outputStream = new ByteArrayOutputStream();
    appender = new RedactingConsoleAppender();
    appender.setContext(context);
    appender.setService("contribution-api");
    appender.setOutputStream(outputStream);
    appender.start();

    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (appender != null) {
      appender.stop();
    }
    if (logger != null) {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName(
      "appender produces valid JSON containing timestamp, level, service, traceId, message")
  void producesStructuredJson() throws Exception {
    LoggingEvent event =
        new LoggingEvent(
            "dev.fondaria.test.RedactingAppenderTest",
            logger,
            Level.INFO,
            "Account balance inquiry processed",
            null,
            null);
    event.setTimeStamp(System.currentTimeMillis());
    Map<String, String> mdc = new HashMap<>();
    mdc.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    mdc.put("transfer_id", "123456789012345678");
    mdc.put("correlation_id", "corr-abc-123");
    event.setMDCPropertyMap(mdc);

    appender.doAppend(event);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(output).isNotBlank();

    JsonNode json = objectMapper.readTree(output);
    assertThat(json.has("timestamp")).isTrue();
    assertThat(json.get("level").asText()).isEqualTo("INFO");
    assertThat(json.get("service").asText()).isEqualTo("contribution-api");
    assertThat(json.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(json.get("transfer_id").asText()).isEqualTo("123456789012345678");
    assertThat(json.get("correlation_id").asText()).isEqualTo("corr-abc-123");
    assertThat(json.get("message").asText()).isEqualTo("Account balance inquiry processed");
  }

  @Test
  @DisplayName("appender redacts sensitive keys in MDC regardless of case, hyphens, or underscores")
  void sanitizesSensitiveMdcKeys() throws Exception {
    LoggingEvent event =
        new LoggingEvent(
            "dev.fondaria.test.RedactingAppenderTest",
            logger,
            Level.INFO,
            "Request context initialized",
            null,
            null);
    event.setTimeStamp(System.currentTimeMillis());
    Map<String, String> mdc = new HashMap<>();
    mdc.put("access_token", "secret-token-value");
    mdc.put("first_name", "Juan");
    mdc.put("document-content", "sensitive body text");
    mdc.put("tenant_id", "safe-tenant-123");
    event.setMDCPropertyMap(mdc);

    appender.doAppend(event);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(output).isNotBlank();

    JsonNode json = objectMapper.readTree(output);
    assertThat(json.get("access_token").asText()).isEqualTo(LogRedactor.REDACTED);
    assertThat(json.get("first_name").asText()).isEqualTo(LogRedactor.REDACTED);
    assertThat(json.get("document-content").asText()).isEqualTo(LogRedactor.REDACTED);
    assertThat(json.get("tenant_id").asText()).isEqualTo("safe-tenant-123");
    assertThat(output)
        .doesNotContain("secret-token-value")
        .doesNotContain("Juan")
        .doesNotContain("sensitive body text");
  }

  @Test
  @DisplayName("appender scrubs PAN, IBAN, email, token, and idempotencyKey from output")
  void scrubsProhibitedFieldsAtAppenderBoundary() throws Exception {
    String pan = "4111222233334444";
    String iban = "MX1234567890123456789012";
    String email = "participant@company.com";
    String token = "secret-jwt-token-xyz";
    String idempotencyKey = "idem-receipt-12345";

    String rawPayload =
        String.format(
            "Processing receipt with pan=%s iban=%s email=%s token=%s idempotencyKey=%s",
            pan, iban, email, token, idempotencyKey);

    LoggingEvent event =
        new LoggingEvent(
            "dev.fondaria.test.RedactingAppenderTest", logger, Level.INFO, rawPayload, null, null);
    event.setTimeStamp(System.currentTimeMillis());

    appender.doAppend(event);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(output).isNotBlank();

    // Verify entire log output bytes contain none of the sensitive values
    assertThat(output)
        .as("Appender output must not leak any sensitive tokens")
        .doesNotContain(pan)
        .doesNotContain(iban)
        .doesNotContain(email)
        .doesNotContain(token)
        .doesNotContain(idempotencyKey);

    // Verify output is still valid JSON
    JsonNode json = objectMapper.readTree(output);
    assertThat(json.get("message").asText()).contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("appender redacts sensitive exception messages in stack trace")
  void redactsExceptionStackTrace() throws Exception {
    RuntimeException exception = new RuntimeException("Invalid token Bearer secret-token-failed");

    LoggingEvent event =
        new LoggingEvent(
            "dev.fondaria.test.RedactingAppenderTest",
            logger,
            Level.ERROR,
            "Failed authentication request",
            exception,
            null);
    event.setTimeStamp(System.currentTimeMillis());

    appender.doAppend(event);

    String output = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(output).isNotBlank();

    JsonNode json = objectMapper.readTree(output);
    assertThat(json.has("exception")).isTrue();
    String stackTrace = json.get("exception").asText();
    assertThat(stackTrace)
        .doesNotContain("secret-token-failed")
        .contains("Bearer " + LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("log volume per simulated transaction is bounded and measured")
  void logVolumePerTransactionIsBoundedAndMeasured() throws Exception {
    outputStream.reset();

    // Simulate multi-step transaction logs
    String[] steps = {
      "Transaction started transfer_id=123456789012345678",
      "Validating accounts for transfer",
      "Ledger transfer posted transfer_id=123456789012345678",
      "Projection updated",
      "Transaction completed transfer_id=123456789012345678 duration_ms=42"
    };

    for (String step : steps) {
      LoggingEvent event =
          new LoggingEvent(
              "dev.fondaria.test.RedactingAppenderTest", logger, Level.INFO, step, null, null);
      event.setTimeStamp(System.currentTimeMillis());
      Map<String, String> mdc =
          Map.of("transfer_id", "123456789012345678", "correlation_id", "corr-999");
      event.setMDCPropertyMap(mdc);
      appender.doAppend(event);
    }

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
      assertThat(node.get("transfer_id").asText()).isEqualTo("123456789012345678");
      validJsonLines.add(line);
      totalBytes += line.getBytes(StandardCharsets.UTF_8).length;
    }

    assertThat(validJsonLines).hasSize(5);
    assertThat(totalBytes)
        .as("Simulated transaction log bytes must be measured and bounded (< 4 KB)")
        .isLessThan(4096);
  }

  @Test
  @DisplayName("log level is configurable per package at runtime without a restart")
  void logLevelConfigurablePerPackageAtRuntimeWithoutRestart() {
    Logger pkgLogger = context.getLogger("dev.fondaria.test.runtime");
    pkgLogger.setAdditive(false);
    pkgLogger.addAppender(appender);

    org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger("dev.fondaria.test.runtime");

    // Initial level is INFO
    pkgLogger.setLevel(Level.INFO);

    outputStream.reset();
    slf4jLogger.debug("debug-suppressed");
    assertThat(outputStream.toString(StandardCharsets.UTF_8)).doesNotContain("debug-suppressed");

    // Reconfigure log level to DEBUG at runtime
    pkgLogger.setLevel(Level.DEBUG);
    outputStream.reset();
    slf4jLogger.debug("debug-visible");
    assertThat(outputStream.toString(StandardCharsets.UTF_8)).contains("debug-visible");

    // Reconfigure log level to WARN at runtime
    pkgLogger.setLevel(Level.WARN);
    outputStream.reset();
    slf4jLogger.info("info-suppressed");
    slf4jLogger.warn("warn-visible");
    String warnOutput = outputStream.toString(StandardCharsets.UTF_8);
    assertThat(warnOutput).doesNotContain("info-suppressed");
    assertThat(warnOutput).contains("warn-visible");

    pkgLogger.detachAppender(appender);
  }
}
