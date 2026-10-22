package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * One (participant, fund) pair with employer-sourced units still outstanding in {@code
 * PARTICIPANT_HOLDINGS_UNVESTED} — a candidate the vesting sweep must evaluate.
 *
 * @param employerUnvestedBasis the sum of every {@code EMPLOYER}-sourced, not-yet-vested unit ever
 *     posted for this (participant, fund) pair ({@code workflow.allotment}, Task 21.2) — the whole
 *     amount a graded schedule's vested fraction applies against, not just what remains today.
 */
public record VestingCandidate(
    OrganizationId organizationId,
    String participantInternalRef,
    FundId fundId,
    Units employerUnvestedBasis) {

  public VestingCandidate {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(employerUnvestedBasis, "employerUnvestedBasis must not be null");
    if (participantInternalRef == null || participantInternalRef.isBlank()) {
      throw new IllegalArgumentException("participantInternalRef must not be blank");
    }
    if (employerUnvestedBasis.isZero()) {
      throw new IllegalArgumentException("employerUnvestedBasis must be positive");
    }
  }

  /** Every field but the fund identifies a person. */
  @Override
  public String toString() {
    return "VestingCandidate[fundId=" + fundId + ", REDACTED]";
  }
}
