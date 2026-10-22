package dev.fondaria.eodjob.snapshot;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PositionUnitsSource} over {@code projection.participant_units}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <h2>One statement per page, not one branch per account</h2>
 *
 * <p>balance-api answers the same "latest row at or before a date" question with a {@code UNION
 * ALL} of one branch per account, which is right there: it reads two accounts for one participant.
 * Here a page is thousands of accounts, and thousands of union branches is a query text measured in
 * megabytes and a plan the planner spends longer building than executing.
 *
 * <p>{@code DISTINCT ON (participant_id, fund_id)} with {@code ORDER BY participant_id, fund_id,
 * effective_date DESC} asks it once for the whole page. The primary key {@code (tenant_id,
 * participant_id, fund_id, effective_date)} serves it directly — {@code tenant_id} is an equality,
 * {@code participant_id} is a scalar-array equality PostgreSQL walks in sorted order, and {@code
 * effective_date} is the descending tail. So the scan is a bounded index walk that keeps only the
 * first row of each account's descending run, and never a sort of the table.
 *
 * <p><b>{@code tenant_id} is not optional, and not for tidiness.</b> It is the partition key: with
 * it, one partition is opened; without it, every tenant's partition is scanned, because {@code
 * participant_id} alone is not the prefix of any index. At a million participants that is the
 * difference between the pass fitting its window and not.
 *
 * <h2>Why the ids arrive as one text parameter</h2>
 *
 * <p>{@code string_to_array(:accountIds, ',')::numeric[]} binds thousands of u128 identifiers as a
 * single parameter, where an {@code IN (...)} list would need one placeholder each and a fresh
 * prepared statement per page size. The ids are canonical unsigned decimal produced by {@code
 * AccountId.asUnsignedDecimal()} — digits only, so the comma separator cannot collide with the
 * data.
 */
@Repository
public class JdbcPositionUnitsSource implements PositionUnitsSource {

  private static final String UNITS_AS_OF =
      """
      SELECT DISTINCT ON (u.participant_id, u.fund_id)
             u.participant_id::text AS account_id, u.units_scaled
        FROM projection.participant_units u
       WHERE u.tenant_id = :tenantId
         AND u.participant_id = ANY (string_to_array(:accountIds, ',')::numeric[])
         AND u.effective_date <= :asOfDate
       ORDER BY u.participant_id, u.fund_id, u.effective_date DESC
      """;

  private final JdbcClient jdbc;

  public JdbcPositionUnitsSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, Long> unitsAsOf(String tenantId, List<String> accountIds, LocalDate asOfDate) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Long> byAccount = new HashMap<>(accountIds.size());
    jdbc.sql(UNITS_AS_OF)
        .param("tenantId", tenantId)
        .param("accountIds", String.join(",", accountIds))
        .param("asOfDate", asOfDate)
        .query(
            rs -> {
              // getString, not getBigDecimal: participant_id is a u128 identifier held in
              // numeric(39,0) and is never arithmetic (docs/ENGINEERING-RULES.md §4 confines
              // BigDecimal to the
              // display boundary).
              byAccount.put(rs.getString("account_id"), rs.getLong("units_scaled"));
            });
    return byAccount;
  }
}
