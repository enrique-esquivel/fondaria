package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.valuation.CachedNav;
import java.util.Objects;

/**
 * One fund's contribution to an organization's summary: every one of its participants' units in
 * that fund, and what those units are worth.
 *
 * <h2>{@code totalValue} is not {@code Money.value(totalUnits, nav)}, and must not be</h2>
 *
 * <p>It is the sum of each participant's own already-rounded lot values — the identical arithmetic
 * {@code FundPosition} performs, run once per participant and added up. That is what Task 6.5's
 * first acceptance criterion demands: <i>totals tie exactly to the sum of participant balances</i>.
 * Rounding the aggregate product instead would produce a figure that no addition of the balances
 * this platform actually served could ever reach.
 *
 * <p><b>The consequence is visible and is accepted</b> ({@code ADR-0015}): a reader multiplying the
 * rendered {@code totalUnits} by the rendered NAV can land within a centavo per participant lot of
 * {@code totalValue}, rather than exactly on it. An employer's summary is a control total for the
 * balances underneath it, and it is those it must agree with.
 *
 * <p><b>Vested and unvested are not broken out here.</b> The employer-facing aggregate reports what
 * the organization's people hold; which lots are yet to vest is a per-participant entitlement
 * question, answered on the participant's own balance. Both are counted, both are valued: vesting
 * governs entitlement, never whether a unit is priced (ADR-0004).
 *
 * @param nav the price these figures were produced at, carried whole so {@code navVersionId} and
 *     {@code stale} travel with them (AGENTS.md §4)
 */
public record OrganizationFundTotal(
    FundId fundId, Units totalUnits, CachedNav nav, Money totalValue) {

  public OrganizationFundTotal {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(totalUnits, "totalUnits must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(totalValue, "totalValue must not be null");
  }
}
