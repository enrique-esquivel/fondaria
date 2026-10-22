package dev.fondaria.statementworker.render;

import dev.fondaria.common.ParticipantId;
import dev.fondaria.statementworker.schedule.StatementJob;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The worker role's per-job unit of work (Task 7.5b): resolve the participant, compute the
 * waterfall's plug, render deterministic bytes, write them, and record completion.
 *
 * <p>Independent of every other job — {@code ADR-0021}'s own description of the fan-out. Nothing
 * here reads another participant's data or blocks on another worker.
 */
@Component
public class StatementRenderer {

  private final ParticipantDirectory participants;
  private final StatementObjectStore objectStore;
  private final CompletionCounter counter;

  public StatementRenderer(
      ParticipantDirectory participants,
      StatementObjectStore objectStore,
      CompletionCounter counter) {
    this.participants = participants;
    this.objectStore = objectStore;
    this.counter = counter;
  }

  /**
   * @throws ParticipantNotEnrolledException if the job names a holding {@code master.participant}
   *     has no row for — a data-integrity gap the caller treats as poison
   */
  public void render(StatementJob job) {
    Objects.requireNonNull(job, "job must not be null");
    ParticipantId participantId =
        participants
            .find(job.participantKey())
            .orElseThrow(
                () ->
                    new ParticipantNotEnrolledException(
                        "period "
                            + job.period()
                            + ": a closing-snapshot holding names a participant"
                            + " master.participant has no row for"));

    ParticipantStatement statement =
        ParticipantStatement.of(
            participantId,
            job.period(),
            job.openingCents(),
            job.contributionsEmployeeCents(),
            job.contributionsEmployerCents(),
            job.contributionsVoluntaryCents(),
            job.closingCents(),
            job.generatedAt());

    RenderedObject rendered = statement.render();
    objectStore.put(objectKey(job.period(), participantId), rendered.bytes());
    counter.recordCompleted(job.period(), job.participantKey().asKey());
  }

  /**
   * {@code statements/period=YYYY-MM/participant=<participantId>.json} — the opaque {@link
   * ParticipantId}, never the internal ref (AGENTS.md §7), and deterministic: the same job always
   * names the same object, so a redelivered job overwrites identical bytes rather than creating a
   * duplicate.
   */
  static String objectKey(String period, ParticipantId participantId) {
    return "statements/period=" + period + "/participant=" + participantId.value() + ".json";
  }
}
