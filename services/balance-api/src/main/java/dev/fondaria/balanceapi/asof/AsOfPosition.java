package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import java.util.Objects;

/**
 * What a participant held in one fund on a past date, and what it was worth at that day's price.
 *
 * <h2>One units figure, not two</h2>
 *
 * <p>The current balance reports vested and unvested separately, because both are live claims a
 * participant is entitled to tell apart (ADR-0004). A historical holding does not: {@code
 * projection.participant_units} is keyed by holding account, so the split exists in the data, but
 * the as-of contract renders a single {@code unitsScaled} per fund and a reader who multiplies it
 * by the rendered NAV must land on the rendered value. So the two accounts' units are summed first
 * and the product is rounded once — which is what ADR-0013 means by "Task 6.4 must round per fund".
 *
 * <h2>The NAV is the live one for that date, and says so</h2>
 *
 * <p>{@code nav} is resolved by {@code FundNavRepository.findLive} — the row for that valuation
 * date that nothing supersedes. If the price originally struck was later corrected (Task 20.5) this
 * is the correction, and {@link #corrected()} is {@code true}. The endpoint never serves a
 * superseded price, and never serves a corrected one silently.
 */
public record AsOfPosition(FundId fundId, Units units, FundNav nav, Money value) {

  public AsOfPosition {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(value, "value must not be null");
  }

  /** Values the combined holding at {@code nav}. The one place this multiplication happens here. */
  public static AsOfPosition of(FundId fundId, Units units, FundNav nav) {
    return new AsOfPosition(fundId, units, nav, Money.value(units, nav.nav()));
  }

  /**
   * Whether the price behind this figure supersedes an earlier one for the same date.
   *
   * <p>{@code supersedes_id} is carried by the <i>correction</i>, never by the original (Task
   * 20.5), so a live row holding it is by construction a corrected price.
   */
  public boolean corrected() {
    return nav.supersedesId() != null;
  }
}
