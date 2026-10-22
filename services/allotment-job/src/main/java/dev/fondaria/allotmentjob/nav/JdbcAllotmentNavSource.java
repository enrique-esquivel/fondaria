package dev.fondaria.allotmentjob.nav;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AllotmentNavSource} over {@code reference.fund_nav}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <p>{@code supersedes_id IS NULL} is exactly what makes a row "original" — the partial unique
 * index on {@code (fund_id, valuation_date) WHERE supersedes_id IS NULL} (V11) guarantees at most
 * one such row per fund and date, matching valuation-api's own {@code JdbcFundNavRepository}.
 *
 * <p>MXN-only (v1, ADR-0004): the struck {@code nav_scaled} is read back as {@link
 * CurrencyCode#MXN} without a stored currency column, the same assumption {@code
 * JdbcFundNavRepository} makes.
 */
@Repository
public class JdbcAllotmentNavSource implements AllotmentNavSource {

  private final JdbcClient jdbc;

  public JdbcAllotmentNavSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(
            """
            SELECT nav_version_id, fund_id, valuation_date, nav_scaled, units_in_issue, source,
                   supersedes_id, reason, published_at
              FROM reference.fund_nav
             WHERE fund_id = :fundId
               AND valuation_date = :valuationDate
               AND supersedes_id IS NULL
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .query(JdbcAllotmentNavSource::mapFundNav)
        .optional();
  }

  private static FundNav mapFundNav(ResultSet rs, int rowNum) throws SQLException {
    return new FundNav(
        rs.getLong("nav_version_id"),
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getObject("valuation_date", LocalDate.class),
        Nav.of(rs.getLong("nav_scaled"), CurrencyCode.MXN),
        Units.of(rs.getLong("units_in_issue")),
        rs.getString("source"),
        (Long) rs.getObject("supersedes_id"),
        rs.getString("reason"),
        rs.getTimestamp("published_at").toInstant());
  }
}
