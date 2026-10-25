package dev.fondaria.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecurityAuditLoggerTest {

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level originalAuditLevel;

  @BeforeEach
  void setUp() {
    auditLogger = (Logger) LoggerFactory.getLogger("security.audit");
    appender = new ListAppender<>();
    appender.start();
    auditLogger.addAppender(appender);
    originalAuditLevel = auditLogger.getLevel();
    auditLogger.setLevel(Level.INFO);
  }

  @AfterEach
  void tearDown() {
    auditLogger.detachAppender(appender);
    auditLogger.setLevel(originalAuditLevel);
  }

  @Test
  @DisplayName("Every decision produces exactly one audit entry")
  void logsDecisionExactlyOnce() {
    SecurityDecision decision =
        SecurityDecision.permitted("user-1", "/v1/participants/123/balance", "GET", "Allowed");
    SecurityAuditLogger.log(decision);

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getFormattedMessage())
        .contains("AUDIT_DECISION")
        .contains("subject=user-1")
        .contains("resource=/v1/participants/123/balance")
        .contains("action=GET")
        .contains("outcome=PERMITTED")
        .contains("reason=Allowed");
  }

  @Test
  @DisplayName("Audit entries contain no tokens, RFCs, CURPs, salaries, or balances")
  void redactsSensitiveInformation() {
    // Attempt to pass sensitive fields inside reason / subject
    String rawJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dummy-signature-token-here";
    String rfc = "rfc: BARM800101XYZ";
    String curp = "CURP: ABCD800101HDFRRN01";
    String salary = "salary: 50000";
    String balance = "balance: 100000";

    SecurityDecision decision =
        SecurityDecision.denied(
            "user-1",
            "/v1/resource",
            "POST",
            "Failed with token="
                + rawJwt
                + ", "
                + rfc
                + ", "
                + curp
                + ", "
                + salary
                + ", "
                + balance);

    SecurityAuditLogger.log(decision);

    assertThat(appender.list).hasSize(1);
    String message = appender.list.get(0).getFormattedMessage();
    assertThat(message)
        .doesNotContain(rawJwt)
        .doesNotContain("BARM800101XYZ")
        .doesNotContain("ABCD800101HDFRRN01")
        .doesNotContain("50000")
        .doesNotContain("100000");
  }
}
