package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.Units;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link HistoricUnitsRepository} over {@code projection.participant_units}, using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>The statement is built by {@link HistoricUnitsStatement}, which carries the reasoning about
 * branches and the index. This class is the execution and the row mapping, and nothing else — it
 * interprets no account id, resolves no fund and computes no value.
 *
 * <p><b>Read-only against a CDC-fed schema</b> (ADR-0011): {@code projection} is never written by a
 * service, and this class holds no {@code INSERT} or {@code UPDATE} at all.
 */
@Repository
public class JdbcHistoricUnitsRepository implements HistoricUnitsRepository {

  private final JdbcClient jdbc;

  public JdbcHistoricUnitsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<HistoricHolding> holdingsAsOf(
      String tenantId, List<HoldingAccountKey> accounts, LocalDate asOfDate) {
    HistoricUnitsStatement statement = HistoricUnitsStatement.build(tenantId, accounts, asOfDate);
    JdbcClient.StatementSpec spec = jdbc.sql(statement.sql());
    for (var parameter : statement.parameters().entrySet()) {
      spec = spec.param(parameter.getKey(), parameter.getValue());
    }
    return spec.query(JdbcHistoricUnitsRepository::mapHolding).list();
  }

  private static HistoricHolding mapHolding(ResultSet rs, int rowNum) throws SQLException {
    return new HistoricHolding(
        new HoldingAccountKey(
            // getString, not getBigDecimal: this is a u128 *identifier* stored as numeric(39,0),
            // and PostgreSQL already renders it in the canonical unsigned decimal the rest of the
            // platform uses. Going through BigDecimal would put a decimal type in a file next to
            // money for no gain (docs/ENGINEERING-RULES.md §4).
            rs.getString("account_id"), rs.getLong("fund_id")),
        rs.getObject("effective_date", LocalDate.class),
        Units.of(rs.getLong("units_scaled")));
  }
}
