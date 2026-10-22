package dev.fondaria.valuationapi.fund;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundDirectory} over {@code reference.fund} and {@code reference.fund_identity}, using
 * {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p>Reads only. {@code reference}'s schema-level grants never include {@code UPDATE} or {@code
 * DELETE} anyway (ADR-0011), but this service does not even {@code INSERT}: the fund registry is
 * contribution-api's to write, and this one only asks whether a fund exists.
 *
 * <p>Resolution mirrors {@code JdbcFundRepository#resolveAsOf} exactly — the latest {@code
 * (effective_from, version)} not after the date asked for. Duplicated deliberately rather than
 * shared: this is four lines of SQL against a stable contract, and the alternative is one service
 * depending on another service's jar for a query.
 */
@Repository
public class JdbcFundDirectory implements FundDirectory {

  private static final String SELECT_FUND =
      """
      SELECT f.fund_id, f.effective_from, f.version, f.name, f.vehicle_type, f.status,
             f.published_at,
             i.unit_ledger_id, i.base_currency, i.unit_scale, i.inception_date,
             i.opening_nav_scaled
        FROM reference.fund f
        JOIN reference.fund_identity i ON i.fund_id = f.fund_id
      """;

  private final JdbcClient jdbc;

  public JdbcFundDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return jdbc.sql(
            SELECT_FUND
                + """
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

  /**
   * The latest version of every fund as of the date, keeping only those that are {@code ACTIVE} and
   * already past inception.
   *
   * <p>{@code DISTINCT ON} rather than a correlated subquery: one pass, and it expresses "the
   * newest version of each fund" directly. The status filter sits outside the {@code DISTINCT ON}
   * so that a fund whose newest version is {@code CLOSED} drops out entirely, rather than falling
   * back to an older {@code ACTIVE} version and being reported as still expecting valuations.
   */
  @Override
  public List<Fund> activeAsOf(LocalDate asOfDate) {
    return jdbc.sql(
            """
            SELECT * FROM (
              SELECT DISTINCT ON (f.fund_id)
                     f.fund_id, f.effective_from, f.version, f.name, f.vehicle_type, f.status,
                     f.published_at,
                     i.unit_ledger_id, i.base_currency, i.unit_scale, i.inception_date,
                     i.opening_nav_scaled
                FROM reference.fund f
                JOIN reference.fund_identity i ON i.fund_id = f.fund_id
               WHERE f.effective_from <= :asOfDate
               ORDER BY f.fund_id, f.effective_from DESC, f.version DESC
            ) current
             WHERE current.status = 'ACTIVE'
               AND current.inception_date <= :asOfDate
             ORDER BY current.fund_id
            """)
        .param("asOfDate", asOfDate)
        .query(JdbcFundDirectory::mapFund)
        .list();
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
