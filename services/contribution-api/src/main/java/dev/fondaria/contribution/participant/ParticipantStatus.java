package dev.fondaria.contribution.participant;

/**
 * Where a participant stands with the platform, distinct from {@link EmploymentStatus}: a
 * participant can be dormant or closed while remaining an employee, and can keep a position after
 * leaving their employer (phase-02b Task 2b.5, deferred).
 *
 * <p>Auto-enrolment only ever writes {@link #ACTIVE}. The other two states belong to Task 2b.5's
 * lifecycle, not yet built; the vocabulary is here because {@code master.participant}'s {@code
 * CHECK} constraint has to name every value it will ever hold, the same way {@code
 * master.organization}'s does.
 */
public enum ParticipantStatus {
  ACTIVE,
  DORMANT,
  CLOSED;

  public static ParticipantStatus parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("participantStatus must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "participantStatus must be one of ACTIVE, DORMANT, CLOSED");
    }
  }
}
