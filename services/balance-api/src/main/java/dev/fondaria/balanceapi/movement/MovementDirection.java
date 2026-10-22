package dev.fondaria.balanceapi.movement;

/**
 * Whether the participant's holding account was debited (units removed) or credited (units
 * received) — {@code MovementDirection} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>Direction is a field, never the sign of the amount.</b> The ledger stores a leg's own
 * positive magnitude and says which account was debited and which credited; a signed "units" number
 * would need a convention about whose point of view it is written from, and every reader would have
 * to remember it. {@code unitsScaled} therefore stays a positive magnitude here too.
 *
 * <p>The ordinal is the tiebreaker within one transfer: {@code DEBIT} before {@code CREDIT}, so
 * both legs of a {@code VESTING} transfer have a stable, total position in the keyset.
 */
public enum MovementDirection {
  DEBIT,
  CREDIT;

  /** {@code 0} for {@code DEBIT}, {@code 1} for {@code CREDIT} — the {@code leg} of the keyset. */
  public int leg() {
    return ordinal();
  }

  static MovementDirection ofLeg(int leg) {
    return switch (leg) {
      case 0 -> DEBIT;
      case 1 -> CREDIT;
      default -> throw new IllegalArgumentException("not a leg: " + leg);
    };
  }
}
