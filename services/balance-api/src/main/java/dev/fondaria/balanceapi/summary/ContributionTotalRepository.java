package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.Money;

/**
 * How much cash an employer's contributions put into the fund over a period (Task 6.5).
 *
 * <h2>Why this reads the projection, and why that is not a balance</h2>
 *
 * <p>The ledger cannot answer this question <i>at all</i>. A contribution's cash passes {@code
 * EMPLOYER_COLLECTIONS → ALLOTMENT_SUSPENSE → FUND_CASH} and, once the batch has fully allotted,
 * {@code EMPLOYER_COLLECTIONS} is back at zero — that return to zero is the control which proves
 * the batch landed (chart-of-accounts.md §6). A balance there says how much is *in flight*, never
 * how much *flowed*. "How much moved between these dates" is history, which is exactly what the
 * CDC-fed projection exists for (ADR-0011), and reading it here is the same allowance Task 6.3
 * takes for the movement history.
 *
 * <p><b>Nothing here is a balance and nothing here is summed into one.</b> The prohibition
 * (docs/ENGINEERING-RULES.md §4) is on deriving what someone holds by adding up rows; this adds up
 * movements to report a flow, and the holdings in the same response come from {@code LedgerPort} as
 * they must.
 */
public interface ContributionTotalRepository {

  /**
   * The cash an employer contributed with a value date inside {@code period}: the sum of every
   * {@code CONTRIBUTION_ALLOTMENT} leg debiting that employer's {@code EMPLOYER_COLLECTIONS}
   * account.
   *
   * <p><b>That one leg, not the whole chain.</b> A {@code CONTRIBUTION_ALLOTMENT} posts the same
   * total twice on the cash ledger — collections to suspense, then suspense to fund cash — so
   * matching on the transfer code alone would report exactly double. Anchoring on the employer's
   * own account also makes the figure structurally per-employer rather than filtered to one.
   *
   * <p><b>Gross of corrections.</b> The transfer-code predicate excludes {@code CORRECTION} (code
   * 8), so a contribution posted and then reversed inside the same month is still reported in full.
   * That is right for a fee or a refund, which move that money without being contributions of it,
   * and wrong for a reversal — but reversals are Phase 14's and nothing posts one yet. Recorded
   * here and in {@code BACKLOG.md} so it is revisited rather than discovered.
   *
   * <p>Zero when nothing was contributed. Never negative: a ledger leg carries a positive magnitude
   * and direction is which account was debited.
   *
   * @param tenantId the partition key — the organization's {@code TenantRef} as unsigned decimal
   * @param employerCollectionsAccountId that organization's {@code EMPLOYER_COLLECTIONS} account id
   *     as unsigned decimal, derived the same way {@code OrganizationService} derived it when it
   *     opened the account
   */
  Money contributionsIn(
      String tenantId, String employerCollectionsAccountId, StatementPeriod period);
}
