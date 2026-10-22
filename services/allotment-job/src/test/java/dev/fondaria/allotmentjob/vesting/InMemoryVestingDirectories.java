package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.allotmentjob.fund.FundDirectory;
import dev.fondaria.allotmentjob.participant.ParticipantDirectory;
import dev.fondaria.allotmentjob.participant.VestingParticipant;
import dev.fondaria.allotmentjob.plan.PlanVersionDirectory;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.PlanVersion;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ParticipantDirectory}, {@link PlanVersionDirectory} and {@link FundDirectory} —
 * one candidate value per key, mirroring {@code allotment}'s {@code InMemoryDirectories}: {@link
 * VestingServiceTest} only ever needs "the version in force as of this date", never a real
 * effective-dating resolution.
 */
final class InMemoryVestingDirectories
    implements ParticipantDirectory, PlanVersionDirectory, FundDirectory {

  private final Map<String, VestingParticipant> participants = new HashMap<>();
  private final Map<String, PlanVersion> planVersions = new HashMap<>();
  private final Map<FundId, Fund> funds = new HashMap<>();

  void givenParticipant(
      OrganizationId organizationId,
      String participantInternalRef,
      VestingParticipant participant) {
    participants.put(organizationId.value() + ":" + participantInternalRef, participant);
  }

  void givenPlanVersion(PlanVersion planVersion) {
    planVersions.put(
        planVersion.organizationId().value() + ":" + planVersion.planId(), planVersion);
  }

  void givenFund(Fund fund) {
    funds.put(fund.fundId(), fund);
  }

  @Override
  public Optional<VestingParticipant> find(
      OrganizationId organizationId, String participantInternalRef) {
    return Optional.ofNullable(
        participants.get(organizationId.value() + ":" + participantInternalRef));
  }

  @Override
  public Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
    return Optional.ofNullable(planVersions.get(organizationId.value() + ":" + planId));
  }

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return Optional.ofNullable(funds.get(fundId));
  }
}
