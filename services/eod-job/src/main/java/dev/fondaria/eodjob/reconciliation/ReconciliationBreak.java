package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.FundId;
import java.util.Objects;
import java.util.Optional;

/**
 * One disagreement between two derivations of the same quantity, quantified exactly.
 *
 * <p><b>Signed and unclamped</b> (AGENTS.md §4). {@code drift = left − right}, and both directions
 * are real findings: "the ledger holds less than the projection claims" is a lost redemption
 * message, "more" is a lost issuance. A control that reported only one of them would be silent on
 * half of what it exists to catch.
 *
 * @param fundId present for {@link BreakScope#FUND_UNITS}, absent for a cash account
 * @param accountId present for {@link BreakScope#CASH_ACCOUNT} as {@code
 *     AccountId.asUnsignedDecimal()}, absent for a fund
 */
public record ReconciliationBreak(
    BreakScope scope,
    Optional<FundId> fundId,
    Optional<Long> unitLedgerId,
    Optional<String> accountId,
    Derivation left,
    long leftAmountScaled,
    Derivation right,
    long rightAmountScaled) {

  public ReconciliationBreak {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(unitLedgerId, "unitLedgerId must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(left, "left must not be null");
    Objects.requireNonNull(right, "right must not be null");
    if (left == right) {
      throw new IllegalArgumentException("a break compares two different derivations");
    }
    if (leftAmountScaled == rightAmountScaled) {
      throw new IllegalArgumentException("a break requires a difference; this one has none");
    }
  }

  /** Units disagreeing for one fund. */
  public static ReconciliationBreak units(
      FundId fundId,
      long unitLedgerId,
      Derivation left,
      long leftAmountScaled,
      Derivation right,
      long rightAmountScaled) {
    return new ReconciliationBreak(
        BreakScope.FUND_UNITS,
        Optional.of(fundId),
        Optional.of(unitLedgerId),
        Optional.empty(),
        left,
        leftAmountScaled,
        right,
        rightAmountScaled);
  }

  /** Cents disagreeing for one cash account. */
  public static ReconciliationBreak cash(String accountId, long ledgerCents, long projectionCents) {
    return new ReconciliationBreak(
        BreakScope.CASH_ACCOUNT,
        Optional.empty(),
        Optional.empty(),
        Optional.of(accountId),
        Derivation.LEDGER_CASH,
        ledgerCents,
        Derivation.PROJECTION_CASH,
        projectionCents);
  }

  /**
   * How far apart the two derivations are, signed. Subtracted exactly: two {@code long} quantities
   * that could each be near the type's limit are subtracted with overflow checking rather than
   * silently wrapping into a small, plausible-looking drift.
   */
  public long driftScaled() {
    return Math.subtractExact(leftAmountScaled, rightAmountScaled);
  }
}
