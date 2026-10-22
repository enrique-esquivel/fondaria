package dev.fondaria.statementworker.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.statementworker.ParticipantKey;
import dev.fondaria.statementworker.schedule.StatementJob;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link StatementRenderer} against fakes — no Postgres, object store or Redis is real here. */
class StatementRendererTest {

  private final Map<ParticipantKey, ParticipantId> directory = new HashMap<>();
  private final Map<String, byte[]> written = new HashMap<>();
  private final Map<String, String> completions = new HashMap<>();

  private final StatementRenderer renderer =
      new StatementRenderer(
          key -> Optional.ofNullable(directory.get(key)),
          written::put,
          (period, participantKey) -> {
            completions.put(period, participantKey);
            return OptionalLong.of(1);
          });

  @Test
  @DisplayName("an unenrolled participant is treated as poison, not silently skipped")
  void unenrolledParticipantIsPoison() {
    StatementJob job = job("ref-unknown");

    assertThatThrownBy(() -> renderer.render(job))
        .isInstanceOf(ParticipantNotEnrolledException.class);
    assertThat(written).isEmpty();
  }

  @Test
  @DisplayName("a resolved participant is rendered, written and counted")
  void resolvedParticipantIsRenderedAndCounted() {
    ParticipantId participantId = ParticipantId.of(UUID.randomUUID());
    StatementJob job = job("ref-1");
    directory.put(job.participantKey(), participantId);

    renderer.render(job);

    String expectedKey = StatementRenderer.objectKey(job.period(), participantId);
    assertThat(written).containsKey(expectedKey);
    assertThat(written.get(expectedKey)).asString().contains(participantId.value().toString());
    assertThat(completions).containsEntry(job.period(), job.participantKey().asKey());
  }

  @Test
  @DisplayName("rendering the same job twice (a redelivered message) writes identical bytes")
  void redeliveredJobWritesIdenticalBytes() {
    ParticipantId participantId = ParticipantId.of(UUID.randomUUID());
    StatementJob job = job("ref-1");
    directory.put(job.participantKey(), participantId);

    renderer.render(job);
    byte[] first = written.get(StatementRenderer.objectKey(job.period(), participantId));
    renderer.render(job);
    byte[] second = written.get(StatementRenderer.objectKey(job.period(), participantId));

    assertThat(second).isEqualTo(first);
  }

  private static StatementJob job(String participantRef) {
    return new StatementJob(
        new OrganizationId(UUID.randomUUID()),
        participantRef,
        "2026-08",
        Instant.parse("2026-08-31T23:00:00.000000Z"),
        45_100_00L,
        90_000L,
        90_000L,
        0L,
        47_382_15L,
        CurrencyCode.MXN);
  }
}
