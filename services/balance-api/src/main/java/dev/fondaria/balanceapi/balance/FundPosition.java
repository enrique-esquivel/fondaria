package dev.fondaria.balanceapi.balance;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.valuation.CachedNav;
import java.util.Objects;

/**
 * What a participant holds in one fund, and what it is worth at that fund's live price.
 *
 * <p><b>Vested and unvested are separate lots and are valued separately.</b> Both are issued units
 * and both mark to market — vesting governs entitlement, never whether a unit is priced (ADR-0004)
 * — but they are different claims and the participant is entitled to see which is which. They are
 * also separate ledger accounts, so keeping them apart here costs nothing and collapsing them would
 * discard information the ledger already distinguishes.
 *
 * <p><b>Each lot is rounded once, and the totals are sums of rounded lots.</b> {@code
 * Money.value(vested) + Money.value(unvested)} can differ by a cent from {@code Money.value(vested
 * + unvested)}; this record commits to the first, so a rendered total always equals the sum of the
 * rendered parts a reader can see. A statement that does not add up is a support call, and the
 * rounding rule that governs which of the two is "right" is a display rule, not a ledger one — no
 * unit and no centavo moves either way.
 *
 * @param nav the price this position was valued at, carried whole so {@code navVersionId} and
 *     {@code stale} travel with the figures they produced (AGENTS.md §4: every value records which
 *     NAV version it used)
 */
public record FundPosition(
    FundId fundId,
    Units vestedUnits,
    Units unvestedUnits,
    CachedNav nav,
    Money vestedValue,
    Money unvestedValue,
    Money value) {

  public FundPosition {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(vestedUnits, "vestedUnits must not be null");
    Objects.requireNonNull(unvestedUnits, "unvestedUnits must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(vestedValue, "vestedValue must not be null");
    Objects.requireNonNull(unvestedValue, "unvestedValue must not be null");
    Objects.requireNonNull(value, "value must not be null");
  }

  /** Values both lots at {@code nav} and sums them. The one place this multiplication happens. */
  public static FundPosition of(FundId fundId, Units vested, Units unvested, CachedNav nav) {
    Money vestedValue = Money.value(vested, nav.nav());
    Money unvestedValue = Money.value(unvested, nav.nav());
    return new FundPosition(
        fundId, vested, unvested, nav, vestedValue, unvestedValue, vestedValue.plus(unvestedValue));
  }

  /**
   * Whether any units are held at all — a fund with none is not reported as a position.
   *
   * <p><b>{@code != 0}, not {@code > 0}.</b> Today a holding cannot be negative: {@code
   * PARTICIPANT_HOLDINGS_*} carry {@code debitsMustNotExceedCredits}, so the adapter computes
   * {@code creditsPosted - debitsPosted} and the floor is enforced inside the ledger. But "every
   * value is signed, never clamp" (AGENTS.md §4) is the rule, and {@code > 0} would silently drop a
   * negative position and thereby <i>overstate</i> the balance — failing open, in the direction
   * that favours us. {@code != 0} keeps it visible for nothing.
   */
  public boolean isHeld() {
    return vestedUnits.scaled() != 0 || unvestedUnits.scaled() != 0;
  }
}
