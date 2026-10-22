package dev.fondaria.balanceapi.fund;

import dev.fondaria.common.FundId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundDirectory} over {@code reference.fund_identity} (AGENTS.md §5 — {@code JdbcClient}, no
 * ORM).
 *
 * <p>Reads the identity half rather than {@code reference.fund}: {@code fund_identity} is one row
 * per fund, fixed at registration and never versioned, so this needs no effective-dating and cannot
 * return the same fund twice. Which <i>version</i> of a fund was current on a given date is a
 * question neither the history nor the as-of holding asks — and {@code unit_ledger_id} lives here
 * precisely because it is a birth fact that no later version may change.
 *
 * <p>Not cached. It is one small indexed read per request against a table with a row per fund — a
 * handful — and both endpoints that use it are already PostgreSQL queries. Caching it would buy
 * nothing measurable and would mean a fund registered today is invisible until a sweep or a
 * restart.
 */
@Repository
public class JdbcFundDirectory implements FundDirectory {

  private static final String SELECT_FUNDS =
      "SELECT fund_id, unit_ledger_id FROM reference.fund_identity";

  private final JdbcClient jdbc;

  public JdbcFundDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<RegisteredFund> listFunds() {
    return jdbc.sql(SELECT_FUNDS)
        .query(
            (rs, rowNum) ->
                new RegisteredFund(
                    new FundId(rs.getObject("fund_id", UUID.class)), rs.getLong("unit_ledger_id")))
        .list();
  }
}
