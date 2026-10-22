package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.summary.OrganizationFundTotal;

/**
 * {@code OrganizationFundSummary} in {@code contracts/openapi/balance-api.yaml}: one fund's share
 * of an organization's summary.
 *
 * <p><b>{@code totalValueCents} is not {@code totalUnitsScaled × navScaled} rounded.</b> It is the
 * sum of the organization's participants' own already-rounded lot values, so that it ties exactly
 * to the balances those participants were served ({@code ADR-0015}). Multiplying the two rendered
 * numbers here lands within a centavo per lot of it, not on it.
 *
 * <p>Every amount is a plain JSON integer — cents for value, scale 1e-8 for units (AGENTS.md §4).
 */
public record OrganizationFundSummaryResponse(
    String fundId, long totalUnitsScaled, long totalValueCents, FundNavResponse nav) {

  public static OrganizationFundSummaryResponse from(OrganizationFundTotal total) {
    return new OrganizationFundSummaryResponse(
        total.fundId().toString(),
        total.totalUnits().scaled(),
        total.totalValue().cents(),
        FundNavResponse.from(total.nav()));
  }
}
