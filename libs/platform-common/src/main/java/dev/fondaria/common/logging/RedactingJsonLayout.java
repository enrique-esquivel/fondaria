package dev.fondaria.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Structured JSON layout enforcing the format specified in {@code AGENTS.md} §7:
 *
 * <ul>
 *   <li>{@code timestamp}: ISO-8601 UTC timestamp
 *   <li>{@code level}: Logback level string (INFO, WARN, ERROR, DEBUG)
 *   <li>{@code service}: service name (configured or derived from environment)
 *   <li>{@code traceId}: trace ID from MDC correlation (or empty string if none)
 *   <li>{@code message}: message text scrubbed by {@link LogRedactor}
 * </ul>
 *
 * <p>Preserves operational identifiers (such as {@code transfer_id} and {@code correlation_id}) as
 * first-class structured fields while scrubbing prohibited sensitive data at the appender boundary.
 */
public class RedactingJsonLayout extends LayoutBase<ILoggingEvent> {

  private static final JsonFactory JSON_FACTORY = new JsonFactory();
  private static final int MAX_MESSAGE_LENGTH = 65_536;

  // Normalized keys (lowercased without hyphens or underscores)
  private static final Set<String> SENSITIVE_MDC_KEYS =
      Set.of(
          "token",
          "password",
          "secret",
          "apikey",
          "accesstoken",
          "refreshtoken",
          "email",
          "pan",
          "clabe",
          "iban",
          "curp",
          "rfc",
          "salary",
          "basesalary",
          "balance",
          "currentbalance",
          "endingbalance",
          "saldo",
          "idempotencykey",
          "documentcontent",
          "filecontent",
          "name",
          "nombre",
          "firstname",
          "lastname",
          "fullname",
          "legalname",
          "username");

  private String service;

  public void setService(String service) {
    this.service = service;
  }

  public String getService() {
    if (service != null && !service.isBlank()) {
      return service;
    }
    String env = System.getenv("SERVICE_NAME");
    if (env != null && !env.isBlank()) {
      return env;
    }
    String prop = System.getProperty("spring.application.name");
    if (prop != null && !prop.isBlank()) {
      return prop;
    }
    return "fondaria-service";
  }

  @Override
  public String doLayout(ILoggingEvent event) {
    StringWriter writer = new StringWriter(256);
    try (JsonGenerator gen = JSON_FACTORY.createGenerator(writer)) {
      gen.writeStartObject();

      // 1. timestamp (ISO-8601 UTC)
      gen.writeStringField("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());

      // 2. level (INFO, WARN, ERROR, DEBUG)
      gen.writeStringField("level", event.getLevel().toString());

      // 3. service
      gen.writeStringField("service", getService());

      // 4. traceId from MDC
      Map<String, String> mdc = event.getMDCPropertyMap();
      String traceId = null;
      if (mdc != null) {
        traceId = mdc.get("traceId");
        if (traceId == null) {
          traceId = mdc.get("trace_id");
        }
      }
      gen.writeStringField("traceId", traceId != null ? traceId : "");

      // Operational standard fields
      gen.writeStringField("logger", event.getLoggerName());
      gen.writeStringField("thread", event.getThreadName());

      // 5. Transfer & correlation IDs (explicitly preserved per AGENTS.md §7)
      if (mdc != null) {
        String transferId = mdc.get("transfer_id");
        if (transferId != null) {
          gen.writeStringField("transfer_id", transferId);
        }
        String correlationId = mdc.get("correlation_id");
        if (correlationId != null) {
          gen.writeStringField("correlation_id", correlationId);
        }

        // Include any other non-standard MDC entries, redacting sensitive ones
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
          String key = entry.getKey();
          if ("traceId".equals(key)
              || "trace_id".equals(key)
              || "transfer_id".equals(key)
              || "correlation_id".equals(key)) {
            continue;
          }
          if (isSensitiveMdcKey(key)) {
            gen.writeStringField(key, LogRedactor.REDACTED);
          } else {
            gen.writeStringField(key, LogRedactor.redact(entry.getValue()));
          }
        }
      }

      // 6. message (capped and redacted)
      String formattedMessage = event.getFormattedMessage();
      if (formattedMessage != null && formattedMessage.length() > MAX_MESSAGE_LENGTH) {
        formattedMessage = formattedMessage.substring(0, MAX_MESSAGE_LENGTH) + "... [TRUNCATED]";
      }
      String redactedMessage = LogRedactor.redact(formattedMessage);
      gen.writeStringField("message", redactedMessage != null ? redactedMessage : "");

      // 7. exception / stack trace (if present, also redacted)
      IThrowableProxy tp = event.getThrowableProxy();
      if (tp != null) {
        String stackTrace = ThrowableProxyUtil.asString(tp);
        if (stackTrace != null && stackTrace.length() > MAX_MESSAGE_LENGTH) {
          stackTrace = stackTrace.substring(0, MAX_MESSAGE_LENGTH) + "... [TRUNCATED]";
        }
        gen.writeStringField("exception", LogRedactor.redact(stackTrace));
      }

      gen.writeEndObject();
    } catch (IOException e) {
      return "{\"error\":\"json_formatting_failed\"}" + CoreConstants.LINE_SEPARATOR;
    }

    return writer.toString() + CoreConstants.LINE_SEPARATOR;
  }

  private boolean isSensitiveMdcKey(String key) {
    if (key == null) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return SENSITIVE_MDC_KEYS.contains(normalized);
  }
}
