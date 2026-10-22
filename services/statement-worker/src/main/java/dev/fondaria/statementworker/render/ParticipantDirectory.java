package dev.fondaria.statementworker.render;

import dev.fondaria.common.ParticipantId;
import dev.fondaria.statementworker.ParticipantKey;
import java.util.Optional;

/**
 * Resolves the internal {@link ParticipantKey} the snapshot files and {@code workflow.allotment}
 * are keyed by to the opaque {@link ParticipantId} external contracts carry — the reverse of {@code
 * balance-api}'s {@code ParticipantDirectory}, which resolves the other way.
 */
public interface ParticipantDirectory {

  Optional<ParticipantId> find(ParticipantKey key);
}
