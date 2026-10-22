package dev.fondaria.allotmentjob.fund;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundDirectory} over {@code reference.fund} and {@code reference.fund_identity}, using
 * {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p>Resolution mirrors {@code JdbcFundRepository#resolveAsOf} and valuation-api's {@code
 * JdbcFundDirectory} exactly — the latest {@code (effective_from, version)} not after the date
 * asked for. Duplicated deliberately rather than shared: four lines of SQL against a stable
 * contract, and the alternative is one service depending on another service's jar for a query.
 */
@Repository
public class JdbcFundDirectory implements FundDirectory {

  private final JdbcClient jdbc;

  public JdbcFundDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return jdbc.sql(
            """
            SELECT f.fund_id, f.effective_from, f.version, f.name, f.vehicle_type, f.status,
                   f.published_at,
                   i.unit_ledger_id, i.base_currency, i.unit_scale, i.inception_date,
                   i.opening_nav_scaled
              FROM reference.fund f
              JOIN reference.fund_identity i ON i.fund_id = f.fund_id
             WHERE f.fund_id = :fundId
               AND f.effective_from <= :asOfDate
             ORDER BY f.effective_from DESC, f.version DESC
             LIMIT 1
            """)
        .param("fundId", fundId.value())
        .param("asOfDate", asOfDate)
        .query(JdbcFundDirectory::mapFund)
        .optional();
  }

  private static Fund mapFund(ResultSet rs, int rowNum) throws SQLException {
    CurrencyCode baseCurrency = CurrencyCode.valueOf(rs.getString("base_currency"));
    return new Fund(
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getObject("effective_from", LocalDate.class),
        rs.getInt("version"),
        rs.getString("name"),
        rs.getString("vehicle_type"),
        baseCurrency,
        rs.getLong("unit_ledger_id"),
        rs.getLong("unit_scale"),
        rs.getObject("inception_date", LocalDate.class),
        Nav.of(rs.getLong("opening_nav_scaled"), baseCurrency),
        FundStatus.parse(rs.getString("status")),
        rs.getTimestamp("published_at").toInstant());
  }
}
