package dev.fondaria.statementworker.render;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The render consumer's halt latch. Set once, never cleared for the life of the process — the same
 * shape {@code projection-worker}'s {@code ProjectionHalt} takes.
 *
 * <p>"Never skip a message you can't process. Send it to the DLQ and halt"
 * (docs/ENGINEERING-RULES.md §4). A {@code statement.jobs.v1} job naming a participant {@code
 * master.participant} has no row for is a genuine data-integrity gap ({@link
 * ParticipantNotEnrolledException}), not a transient failure: {@link StatementJobListener} hands
 * the bytes to {@link DeadLetterPublisher}, trips this latch, and the container-stopping error
 * handler stops every worker listener container.
 */
@Component
public class RenderHalt {

  private static final Logger LOGGER = LoggerFactory.getLogger(RenderHalt.class);

  private final AtomicReference<String> reason = new AtomicReference<>();

  public void trigger(String haltReason) {
    if (reason.compareAndSet(null, haltReason)) {
      LOGGER.error("statement render HALTED: {}", haltReason);
    } else {
      LOGGER.error("statement render already halted; additional condition: {}", haltReason);
    }
  }

  public boolean isHalted() {
    return reason.get() != null;
  }

  public Optional<String> reason() {
    return Optional.ofNullable(reason.get());
  }
}
