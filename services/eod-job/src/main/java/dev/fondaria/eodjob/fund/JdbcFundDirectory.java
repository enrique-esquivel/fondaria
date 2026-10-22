package dev.fondaria.eodjob.fund;

import dev.fondaria.common.FundId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundDirectory} over {@code reference.fund_identity}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <p>No join to {@code reference.fund} and no {@code effective_from} predicate, unlike every other
 * directory in this platform: those exist to resolve which <i>version</i> of a fund's name, vehicle
 * type or status was live on a date, and the close asks about none of them. Identity is not
 * versioned (Task 20.1), so this is the whole query.
 */
@Repository
public class JdbcFundDirectory implements FundDirectory {

  private final JdbcClient jdbc;

  public JdbcFundDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<RegisteredFund> allFunds() {
    return jdbc.sql(
            """
            SELECT fund_id, unit_ledger_id
              FROM reference.fund_identity
             ORDER BY unit_ledger_id
            """)
        .query(JdbcFundDirectory::mapFund)
        .list();
  }

  private static RegisteredFund mapFund(ResultSet rs, int rowNum) throws SQLException {
    return new RegisteredFund(
        new FundId(rs.getObject("fund_id", UUID.class)), rs.getLong("unit_ledger_id"));
  }
}
