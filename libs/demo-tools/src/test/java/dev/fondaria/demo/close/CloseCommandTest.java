package dev.fondaria.demo.close;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CloseCommandTest {

  @Test
  void aFailedStatusReadIsReportedByItsCauseNotTheEchoedCommandAndSql() {
    String message =
        "kubectl exec svc/postgres-rw -c postgres -- psql -c SELECT coalesce(\n"
            + "       EXISTS (SELECT 1 FROM workflow.close_signoff)\n"
            + " timed out after PT30S\n";

    assertThat(CloseCommand.lastLine(message)).isEqualTo("timed out after PT30S");
  }

  @Test
  void aOneLineOrMissingMessageIsKeptAsItIs() {
    assertThat(CloseCommand.lastLine("connection refused")).isEqualTo("connection refused");
    assertThat(CloseCommand.lastLine(null)).isEmpty();
  }
}
