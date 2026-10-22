package dev.fondaria.rules;

/**
 * The one rule step 5 enforces: an employer's contribution on a row must equal its matching
 * employee contribution scaled by the plan's ratio (docs/DOMAIN.md point 5 — parity is validated
 * per participant, never averaged across a batch).
 *
 * <p>Cents only, ever. Cross-multiplying {@code employeeCents} by the ratio and rounding half-up —
 * the same convention {@code dev.fondaria.common.Money}/{@code Nav} use — is how a fraction is
 * applied to an integer amount without introducing {@code double} or {@code BigDecimal} into the
 * computation (docs/ENGINEERING-RULES.md §4).
 */
public final class MatchingRatioRule {

  private MatchingRatioRule() {}

  /**
   * @return the employer amount, in cents, the plan's ratio requires for the given employee amount
   *     — rounded half-up to the nearest cent.
   */
  public static long expectedEmployerCents(PlanVersion plan, long employeeCents) {
    if (employeeCents < 0) {
      throw new IllegalArgumentException("employeeCents must not be negative");
    }
    long numerator = Math.multiplyExact(employeeCents, (long) plan.employerMatchRatioNumerator());
    long denominator = plan.employerMatchRatioDenominator();
    // floor((2a + b) / (2b)) is half-up rounding of a/b for non-negative a, b.
    long doubledNumerator = Math.multiplyExact(numerator, 2L);
    long doubledDenominator = Math.multiplyExact(denominator, 2L);
    return Math.addExact(doubledNumerator, denominator) / doubledDenominator;
  }

  /**
   * @return whether {@code employerCents} deviates from what the plan's ratio requires.
   */
  public static boolean breaches(PlanVersion plan, long employeeCents, long employerCents) {
    return employerCents != expectedEmployerCents(plan, employeeCents);
  }
}
