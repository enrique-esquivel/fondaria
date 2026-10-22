package dev.fondaria.contribution.organization;

/**
 * Where a client organization stands with the platform. Distinct from anything a participant has:
 * an organization being suspended says nothing about whether its employees still hold units.
 *
 * <p>Deliberately three states and no more. {@code CLOSED} is terminal in the sense that no new
 * money is accepted; it is <b>not</b> a delete, and it never releases the organization's id or its
 * ledger accounts, which continue to exist for as long as any participant of theirs holds a
 * position.
 */
public enum OrganizationStatus {

  /** Registered and able to remit. */
  ACTIVE,

  /** Temporarily barred from remitting — a commercial or compliance hold. Reversible. */
  SUSPENDED,

  /**
   * The relationship has ended. No new remittance is accepted; existing positions are untouched.
   */
  CLOSED;

  public static OrganizationStatus parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("status must be one of ACTIVE, SUSPENDED, CLOSED");
    }
  }
}
