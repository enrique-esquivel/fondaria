package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.ledger.LedgerCodes;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ProjectionTotals} over the {@code projection} schema, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <h2>Why neither query filters on a date</h2>
 *
 * <p>The ledger has no as-of read: {@code LedgerPort.lookupAccount} answers <i>now</i>, and nothing
 * in TigerBeetle answers "as at the close of business on Tuesday". So the tie-out is live against
 * live, and the business date on the run is the close the pass belongs to rather than a filter
 * applied to either side. Restricting the projection to {@code effective_date <= D} while reading
 * the ledger unrestricted would report every movement made after D as drift — a break manufactured
 * by the query, on a book that balances.
 *
 * <h2>Why {@code DISTINCT ON} for units and a {@code UNION ALL} for cash</h2>
 *
 * <p>{@code participant_units} is sparse — one row per holding <i>change</i> — so the current
 * holding is the latest row per {@code (tenant_id, participant_id, fund_id)}. That is exactly what
 * {@code DISTINCT ON} over the primary key's own column order returns, and the primary key {@code
 * (tenant_id, participant_id, fund_id, effective_date)} serves the ordering directly.
 *
 * <p>{@code transfer} is dense — one row per movement, never a balance — so a cash account's
 * position is its credits minus its debits. Each transfer contributes to two accounts, which is
 * what the {@code UNION ALL} expresses: one branch per leg, the debit leg negated, then one
 * grouping.
 *
 * <h2>Why both sums are cast back to {@code bigint} in the query</h2>
 *
 * <p>{@code SUM} over {@code bigint} returns {@code numeric} in PostgreSQL, so the sum itself
 * cannot silently wrap. Casting it back narrows it where the overflow is loud — PostgreSQL raises
 * {@code bigint out of range} — rather than reading a {@code BigDecimal} into Java to narrow it
 * there. A total genuinely too large for the platform's own {@code long} is not a number this
 * platform can hold on either side of the comparison, so failing the pass is the only honest
 * outcome; and doing it in SQL keeps {@code BigDecimal} out of a money path entirely (AGENTS.md §4
 * confines it to the display boundary). {@code account_id} is cast to {@code text} for the same
 * reason: it is a u128 identifier stored in {@code numeric(39,0)}, and nothing ever does arithmetic
 * on it.
 */
@Repository
public class JdbcProjectionTotals implements ProjectionTotals {

  private static final String UNITS_BY_FUND =
      """
      SELECT latest.fund_id, SUM(latest.units_scaled)::bigint AS units_scaled
        FROM (SELECT DISTINCT ON (tenant_id, participant_id, fund_id)
                     fund_id, units_scaled
                FROM projection.participant_units
               ORDER BY tenant_id, participant_id, fund_id, effective_date DESC) latest
       GROUP BY latest.fund_id
      """;

  private static final String CASH_BY_ACCOUNT =
      """
      SELECT leg.account_id::text AS account_id, SUM(leg.delta)::bigint AS delta
        FROM (SELECT credit_account_id AS account_id, amount_scaled AS delta
                FROM projection.transfer
               WHERE ledger_id = :cashLedgerId
               UNION ALL
              SELECT debit_account_id AS account_id, -amount_scaled AS delta
                FROM projection.transfer
               WHERE ledger_id = :cashLedgerId) leg
       GROUP BY leg.account_id
      """;

  private final JdbcClient jdbc;

  public JdbcProjectionTotals(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<Long, Long> unitsByFundLedger() {
    Map<Long, Long> totals = new HashMap<>();
    jdbc.sql(UNITS_BY_FUND)
        .query(
            rs -> {
              totals.put(rs.getLong("fund_id"), rs.getLong("units_scaled"));
            });
    return Map.copyOf(totals);
  }

  @Override
  public Map<String, Long> cashByAccount() {
    Map<String, Long> totals = new HashMap<>();
    jdbc.sql(CASH_BY_ACCOUNT)
        .param("cashLedgerId", LedgerCodes.CASH_LEDGER_ID)
        .query(
            rs -> {
              totals.put(rs.getString("account_id"), rs.getLong("delta"));
            });
    return Map.copyOf(totals);
  }
}
