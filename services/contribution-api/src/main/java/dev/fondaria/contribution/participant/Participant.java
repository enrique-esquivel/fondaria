package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A pseudonymous participant as held in {@code master.participant} (Task 2b.3, part two).
 *
 * <p>Created by auto-enrolment from a {@code ContributionFile.ParticipantRow} — {@code
 * contribution-file-v2.md} §3 is what this record's fields are drawn from, one for one. A row
 * naming a participant already known is matched on {@link #internalRef()} rather than creating a
 * second one; see {@link ParticipantRepository#insertIfAbsent}.
 *
 * <p><b>{@link #employmentStartDate()} and {@link #enrolmentDate()} are distinct and are never
 * collapsed into one.</b> The first is the seniority clock a graded vesting schedule runs off
 * (ADR-0004); the second is when the participant joined the plan. Conflating them misallocates
 * employer money at withdrawal, silently and in either direction — phase-02b Task 2b.3.
 *
 * <p>No name, {@code RFC} or {@code CURP} here — this record stays exactly what phase-02b enrolled.
 * Task 9.5 (phase-09-identity.md) reverses that scope decision at the platform owner's call, but
 * puts identity fields in a companion table, {@code master.participant_identity}, encrypted per
 * participant rather than added as plaintext columns here. See {@code ParticipantIdentity} and
 * {@code ParticipantIdentityService}.
 */
public record Participant(
    OrganizationId organizationId,
    String internalRef,
    String planId,
    String payrollRef,
    ParticipantStatus status,
    EmploymentStatus employmentStatus,
    LocalDate employmentStartDate,
    LocalDate enrolmentDate,
    Optional<LocalDate> employmentEndDate,
    Instant createdAt,
    Instant updatedAt) {

  public Participant {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
    Objects.requireNonNull(employmentStartDate, "employmentStartDate must not be null");
    Objects.requireNonNull(enrolmentDate, "enrolmentDate must not be null");
    Objects.requireNonNull(employmentEndDate, "employmentEndDate must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    internalRef = requireText(internalRef, "internalRef");
    planId = requireText(planId, "planId");
    payrollRef = requireText(payrollRef, "payrollRef");
    if (enrolmentDate.isBefore(employmentStartDate)) {
      // Never rendered: the dates identify a person, same as internalRef and payrollRef.
      throw new IllegalArgumentException("enrolmentDate must not be before employmentStartDate");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  /** Every field but the organization identifies a person (AGENTS.md §7). */
  @Override
  public String toString() {
    return "Participant[organizationId=" + organizationId + ", REDACTED]";
  }
}
