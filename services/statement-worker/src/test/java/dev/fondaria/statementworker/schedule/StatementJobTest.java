package dev.fondaria.statementworker.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatementJobTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("toJson/fromJson round-trips every field")
  void roundTrips() {
    StatementJob job =
        new StatementJob(
            new OrganizationId(UUID.randomUUID()),
            "internal-ref-1",
            "2026-08",
            Instant.parse("2026-08-31T23:00:00.123456Z"),
            45_100_00L,
            90_000L,
            90_000L,
            0L,
            47_382_15L,
            CurrencyCode.MXN);

    StatementJob decoded = StatementJob.fromJson(MAPPER, job.toJson());

    assertThat(decoded).isEqualTo(job);
  }

  @Test
  @DisplayName("a malformed message is reported as such, not swallowed")
  void malformedMessageFailsDecode() {
    assertThatThrownBy(() -> StatementJob.fromJson(MAPPER, "{\"not\":\"a job\"}"))
        .isInstanceOf(MalformedStatementJobException.class);
  }

  @Test
  @DisplayName("the participant key is derived from organizationId and participantInternalRef")
  void participantKeyDerivation() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    StatementJob job =
        new StatementJob(
            orgId, "ref-42", "2026-08", Instant.now(), 0L, 0L, 0L, 0L, 0L, CurrencyCode.MXN);

    assertThat(job.participantKey().organizationId()).isEqualTo(orgId);
    assertThat(job.participantKey().participantInternalRef()).isEqualTo("ref-42");
  }
}
