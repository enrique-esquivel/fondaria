package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.summary.OrganizationSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code OrganizationSummary} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>{@code totalValueCents} is exactly the sum of {@code funds[].totalValueCents}</b>, which is
 * in turn exactly the sum of every one of this organization's participants' own {@code
 * investedValueCents}. That is the tie Task 6.5 exists to provide.
 *
 * <p><b>{@code periodStart} and {@code periodEnd} say which days {@code
 * contributionsThisPeriodCents} covers</b> (Task 6.5 added them to the contract). A period figure
 * whose window is invisible cannot be checked against anything, and this platform's rule is that a
 * calculation is reproducible years later from what was stored beside it (AGENTS.md §4).
 *
 * <p>Every amount is a plain JSON integer of MXN cents; units are integers at scale 1e-8. Never a
 * decimal, never a float.
 *
 * @param stale {@code true} when at least one fund's price is older than the configured age. Served
 *     marked, never silently, and never withheld.
 */
public record OrganizationSummaryResponse(
    String organizationId,
    Instant asOf,
    int totalParticipants,
    long totalValueCents,
    long contributionsThisPeriodCents,
    LocalDate periodStart,
    LocalDate periodEnd,
    List<OrganizationFundSummaryResponse> funds,
    boolean stale) {

  public static OrganizationSummaryResponse from(OrganizationSummary summary) {
    return new OrganizationSummaryResponse(
        summary.organizationId().toString(),
        summary.asOf(),
        summary.totalParticipants(),
        summary.totalValue().cents(),
        summary.contributionsThisPeriod().cents(),
        summary.period().start(),
        summary.period().end(),
        summary.funds().stream().map(OrganizationFundSummaryResponse::from).toList(),
        summary.stale());
  }
}
