package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link SnapshotNavSource} over {@code reference.fund_nav}, using {@code JdbcClient} (AGENTS.md §5
 * — no ORM).
 *
 * <h2>"Live" is the row nothing points at</h2>
 *
 * <p>A correction carries {@code supersedes_id} pointing back at the row it replaces (Task 20.5),
 * so the superseded row is the one <i>named</i> by something and the live row is the one named by
 * nothing. That is what the {@code NOT EXISTS} expresses. Reading {@code supersedes_id IS NULL}
 * instead would return the <i>original</i> strike — the exact opposite — and would value a whole
 * day's book at a price the platform has already withdrawn.
 *
 * <h2>Why the latest date at or before, rather than the date itself</h2>
 *
 * <p>NAV frequency is unsettled ({@code O-21}) and a fund is not struck on a day nothing priced it,
 * so "the price on Tuesday" is the most recent strike at or before Tuesday. {@code DISTINCT ON
 * (fund_id)} with {@code valuation_date DESC} is exactly that, and {@code fund_nav_fund_date_idx}
 * serves the ordering. {@code nav_version_id DESC} breaks a tie no unique index forbids: two live
 * rows for one fund and date cannot both exist today, but the tiebreak makes the answer
 * deterministic rather than resting on that.
 *
 * <p>The join to {@code reference.fund_identity} supplies the fund's base currency. {@code Nav}
 * carries one, and hardcoding {@code MXN} here would be a rule about the platform written into a
 * query rather than read from the fund that was registered.
 */
@Repository
public class JdbcSnapshotNavSource implements SnapshotNavSource {

  private static final String LIVE_NAVS_AS_OF =
      """
      SELECT DISTINCT ON (n.fund_id)
             n.fund_id, n.nav_version_id, n.nav_scaled, n.valuation_date, f.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity f ON f.fund_id = n.fund_id
       WHERE n.valuation_date <= :businessDate
         AND NOT EXISTS (SELECT 1
                           FROM reference.fund_nav c
                          WHERE c.supersedes_id = n.nav_version_id)
       ORDER BY n.fund_id, n.valuation_date DESC, n.nav_version_id DESC
      """;

  private final JdbcClient jdbc;

  public JdbcSnapshotNavSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<FundId, SnapshotNav> liveNavsAsOf(LocalDate businessDate) {
    Map<FundId, SnapshotNav> byFund = new HashMap<>();
    jdbc.sql(LIVE_NAVS_AS_OF)
        .param("businessDate", businessDate)
        .query(
            (ResultSet rs) -> {
              SnapshotNav nav = mapNav(rs);
              byFund.put(nav.fundId(), nav);
            });
    return Map.copyOf(byFund);
  }

  private static SnapshotNav mapNav(ResultSet rs) throws SQLException {
    return new SnapshotNav(
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getLong("nav_version_id"),
        Nav.of(rs.getLong("nav_scaled"), CurrencyCode.valueOf(rs.getString("base_currency"))),
        rs.getObject("valuation_date", LocalDate.class));
  }
}
