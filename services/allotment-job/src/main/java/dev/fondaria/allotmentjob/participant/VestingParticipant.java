package dev.fondaria.allotmentjob.participant;

import java.time.LocalDate;
import java.util.Objects;

/**
 * As much of {@code master.participant} (Task 2b.3) as the vesting job needs: which plan a
 * participant belongs to, and the seniority clock a graded vesting schedule runs off (ADR-0004).
 *
 * <p>Narrower than contribution-api's own {@code Participant} — no payroll ref, no statuses, no
 * enrolment date — the same reason {@code StagedBatch} is narrower than contribution-api's own
 * {@code ContributionBatch}: this service reads what {@link
 * dev.fondaria.allotmentjob.vesting.VestingService} needs and nothing else.
 *
 * <p>{@code employmentStartDate}, not {@code enrolmentDate} — conflating the two misallocates
 * employer money, silently and in either direction (phase-02b Task 2b.3).
 */
public record VestingParticipant(String planId, LocalDate employmentStartDate) {

  public VestingParticipant {
    Objects.requireNonNull(employmentStartDate, "employmentStartDate must not be null");
    if (planId == null || planId.isBlank()) {
      throw new IllegalArgumentException("planId must not be blank");
    }
  }
}
