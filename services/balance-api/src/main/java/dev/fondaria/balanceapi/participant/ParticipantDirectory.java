package dev.fondaria.balanceapi.participant;

import dev.fondaria.common.ParticipantId;
import java.util.Optional;

/**
 * Resolves the opaque {@code participantId} a URL carries into the {@link ParticipantScope} the
 * ledger derives holding accounts from.
 *
 * <p>Read-only and deliberately narrower than contribution-api's {@code ParticipantRepository} —
 * the same narrowing {@code FundDirectory} applies to {@code FundRepository}. This service must
 * never enrol, update or close a participant, and an interface that cannot express those operations
 * says so better than a comment.
 *
 * <p><b>The mapping is immutable</b>, which is what makes {@link CachingParticipantDirectory}
 * sound: {@code participant_id} is assigned once by a column default and never reassigned ({@code
 * V22__participant_id.sql}), and neither is {@code participant_internal_ref}.
 */
public interface ParticipantDirectory {

  /**
   * Empty when no participant carries that id — never a zero-holding scope, which is a different
   * and misleading claim.
   */
  Optional<ParticipantScope> find(ParticipantId participantId);
}
