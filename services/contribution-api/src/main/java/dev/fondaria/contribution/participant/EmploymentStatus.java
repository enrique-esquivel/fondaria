package dev.fondaria.contribution.participant;

/**
 * Whether a participant is still employed by the organization, distinct from {@link
 * ParticipantStatus} (phase-02b §2: "an employee can leave the organization and keep a balance").
 *
 * <p>Auto-enrolment only ever writes {@link #ACTIVE}; {@link #TERMINATED} is set by Task 2b.5's
 * lifecycle, not yet built.
 */
public enum EmploymentStatus {
  ACTIVE,
  TERMINATED;

  public static EmploymentStatus parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("employmentStatus must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("employmentStatus must be one of ACTIVE, TERMINATED");
    }
  }
}
