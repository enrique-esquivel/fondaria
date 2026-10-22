package dev.fondaria.allotmentjob.participant;

import dev.fondaria.common.OrganizationId;
import java.util.Optional;

/**
 * Read-only access to {@code master.participant} (Task 2b.3).
 *
 * <p>Narrower than contribution-api's own {@code ParticipantRepository} on purpose, the same reason
 * {@link dev.fondaria.allotmentjob.fund.FundDirectory} and {@link
 * dev.fondaria.allotmentjob.plan.PlanVersionDirectory} are: this service never enrols or writes a
 * participant, only resolves the plan and employment start date a vesting schedule runs off.
 */
public interface ParticipantDirectory {

  Optional<VestingParticipant> find(OrganizationId organizationId, String participantInternalRef);
}
