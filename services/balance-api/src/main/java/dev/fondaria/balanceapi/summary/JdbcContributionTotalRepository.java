package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.ledger.LedgerCodes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ContributionTotalRepository} over {@code projection.transfer}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <h2>How the statement is pruned and served</h2>
 *
 * <ul>
 *   <li><b>{@code tenant_id} equality</b> selects the organization's own {@code LIST} partition,
 *       and <b>the {@code value_date} range</b> selects the monthly {@code RANGE} leaves under it
 *       ({@code V20}). A calendar-month period touches one leaf.
 *   <li><b>{@code debit_account_id} equality</b> is served by {@code transfer_by_debit_account}
 *       ({@code V23}), the index Task 6.3 created for the movement history's debit branch.
 * </ul>
 *
 * <p><b>The ledger and code predicates are correctness, not pruning.</b> {@code
 * EMPLOYER_COLLECTIONS} is a cash account and only a cash transfer can debit it, so {@code
 * ledger_id} excludes nothing today — it is there so that a future unit-ledger account colliding on
 * this id could not be added into a peso figure. {@code code} excludes a {@code CORRECTION} or a
 * refund debiting the same account, which are movements of that money but not contributions of it.
 *
 * <p><b>The sum is cast back to {@code bigint} in SQL.</b> PostgreSQL widens {@code SUM(bigint)} to
 * {@code numeric}, and reading that through {@code BigDecimal} would put a decimal type in the path
 * of a cash figure (docs/ENGINEERING-RULES.md §4). The cast keeps it an integer all the way to
 * {@link Money}, and a total that genuinely exceeded a {@code long} <i>fails</i> with {@code bigint
 * out of range} rather than narrowing silently, which is what matters: a wrong number about money
 * is the outcome being excluded.
 *
 * <p>It is not, however, a <i>loud</i> failure at the edge. Every {@code DataAccessException} on
 * this path — a permanent one like this or a broken statement, as much as a real outage — is
 * rendered by the handler as a retryable {@code 503}. That mapping is shared with the other three
 * endpoints of this service and is recorded in {@code BACKLOG.md} as a gap to close across all four
 * at once, rather than forked here.
 */
@Repository
public class JdbcContributionTotalRepository implements ContributionTotalRepository {

  private static final String SUM_CONTRIBUTIONS =
      """
      SELECT COALESCE(SUM(t.amount_scaled), 0)::bigint AS contributed_cents
        FROM projection.transfer t
       WHERE t.tenant_id = :tenantId
         AND t.value_date >= :periodStart
         AND t.value_date <= :periodEnd
         AND t.ledger_id = :cashLedgerId
         AND t.code = :contributionCode
         AND t.debit_account_id = CAST(:employerCollections AS numeric)
      """;

  private final JdbcClient jdbc;

  public JdbcContributionTotalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Money contributionsIn(
      String tenantId, String employerCollectionsAccountId, StatementPeriod period) {
    long cents =
        jdbc.sql(SUM_CONTRIBUTIONS)
            .param("tenantId", tenantId)
            .param("periodStart", period.start())
            .param("periodEnd", period.end())
            .param("cashLedgerId", LedgerCodes.CASH_LEDGER_ID)
            .param("contributionCode", LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code())
            .param("employerCollections", employerCollectionsAccountId)
            .query(Long.class)
            .single();
    return Money.of(cents, CurrencyCode.MXN);
  }
}
