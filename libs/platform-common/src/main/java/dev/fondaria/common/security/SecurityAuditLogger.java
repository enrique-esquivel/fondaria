package dev.fondaria.common.security;

import dev.fondaria.common.logging.LogRedactor;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated audit logger for authorization decisions (Task 9.3).
 *
 * <p>Enforces:
 *
 * <ul>
 *   <li>Every decision appears exactly once in the audit log.
 *   <li>Structured JSON-safe format with subject, resource, action, outcome, reason.
 *   <li>Audit entries contain no token, salary, balance, RFC, CURP or sensitive personal data.
 * </ul>
 */
public final class SecurityAuditLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger("security.audit");

  private SecurityAuditLogger() {}

  public static void log(SecurityDecision decision) {
    Objects.requireNonNull(decision, "decision");
    String safeSubject = LogRedactor.redact(sanitizeField(decision.subject()));
    String safeResource = LogRedactor.redact(sanitizeField(decision.resource()));
    String safeAction = LogRedactor.redact(sanitizeField(decision.action()));
    String safeOutcome = decision.outcome().name();
    String safeReason = LogRedactor.redact(sanitizeField(decision.reason()));

    String logLine =
        "AUDIT_DECISION subject="
            + safeSubject
            + " resource="
            + safeResource
            + " action="
            + safeAction
            + " outcome="
            + safeOutcome
            + " reason="
            + safeReason;

    LOGGER.info("{}", LogRedactor.redact(logLine));
  }

  private static String sanitizeField(String input) {
    if (input == null || input.isBlank()) {
      return "-";
    }
    return input.replaceAll("[\\r\\n\\t]", " ").trim();
  }
}
