package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.summary.OrganizationSummaryService;
import dev.fondaria.common.OrganizationId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/organizations/{organizationId}/summary} — Task 6.5's endpoint, as specified in
 * {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>Thin on purpose: parse the path variable, delegate, map the result. Every decision worth
 * testing is in {@code OrganizationSummaryService}, and the failure translations are in {@link
 * OrganizationSummaryExceptionHandler}.
 *
 * <p><b>Unauthenticated in v1</b>, like every other endpoint on this platform, and it must not be
 * exposed outside the cluster until Phase 9/10 lands. That is a sharper caveat here than on a
 * participant's balance: an organization id is derived from a tax identifier and is therefore
 * guessable by anyone holding the namespace and the employer's RFC, where a participant id is
 * random precisely so it is not. What the service does guarantee today is that a request naming one
 * organization can return nothing but that organization's data.
 *
 * <p><b>The {@code @PathVariable} is named explicitly</b>, as on every other controller here: the
 * build does not compile with {@code -parameters}, so an unnamed one resolves to nothing at runtime
 * and every request fails.
 */
@RestController
public class OrganizationSummaryController {

  private final OrganizationSummaryService summaries;

  public OrganizationSummaryController(OrganizationSummaryService summaries) {
    this.summaries = summaries;
  }

  @GetMapping("/v1/organizations/{organizationId}/summary")
  public OrganizationSummaryResponse getOrganizationSummary(
      @PathVariable("organizationId") String organizationId) {
    OrganizationId orgId = parse(organizationId);
    return OrganizationSummaryResponse.from(summaries.summaryOf(orgId));
  }

  /**
   * Narrows {@code OrganizationId.parse}'s {@link IllegalArgumentException} to a type that means
   * only this. Everything else raising that type downstream is a fault in our own data, not in the
   * caller's request — see {@link MalformedOrganizationIdException}.
   */
  private static OrganizationId parse(String organizationId) {
    try {
      return OrganizationId.parse(organizationId);
    } catch (IllegalArgumentException e) {
      throw new MalformedOrganizationIdException(e);
    }
  }
}
