package dev.fondaria.balanceapi.nav;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundNavRepository} over {@code reference.fund_nav}, with the two write operations refused.
 *
 * <p><b>Striking and correcting a price belong to valuation-api, and only there.</b> This service
 * reads prices to value holdings; a NAV written from a read path would be a second source of truth
 * for the number every balance in a fund is multiplied by. The refusal is structural rather than a
 * matter of nobody happening to call them — {@link #strike} and {@link #correct} throw.
 *
 * <p>The read half is implemented in full and duplicates valuation-api's SQL rather than importing
 * it, the same cross-service pattern {@code allotment-job}'s own {@code ParticipantDirectory} uses:
 * services do not depend on each other's source trees. <b>The {@code NOT EXISTS} predicate is the
 * load-bearing part and must not drift into {@code supersedes_id IS NULL}</b> — the correction
 * carries the pointer, so the live row is the one nothing points at, and the null-holding row is
 * the superseded original (Task 20.5).
 */
@Repository
public class ReadOnlyFundNavRepository implements FundNavRepository {

  private static final String COLUMNS =
      """
      SELECT n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
      """;

  private static final String LIVE =
      " AND NOT EXISTS (SELECT 1 FROM reference.fund_nav s WHERE s.supersedes_id = n.nav_version_id)";

  private static final String SELECT_ORIGINAL =
      COLUMNS
          + " WHERE n.fund_id = :fundId AND n.valuation_date = :valuationDate"
          + " AND n.supersedes_id IS NULL";

  private static final String SELECT_LIVE_FOR_DATE =
      COLUMNS + " WHERE n.fund_id = :fundId AND n.valuation_date = :valuationDate" + LIVE;

  private static final String SELECT_LATEST_LIVE =
      COLUMNS
          + " WHERE n.fund_id = :fundId"
          + LIVE
          + " ORDER BY n.valuation_date DESC, n.nav_version_id DESC LIMIT 1";

  /** One row per fund in one round trip — what the cache sweep reads. */
  private static final String SELECT_LATEST_LIVE_PER_FUND =
      """
      SELECT DISTINCT ON (n.fund_id)
             n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
       WHERE NOT EXISTS (SELECT 1
                           FROM reference.fund_nav s
                          WHERE s.supersedes_id = n.nav_version_id)
       ORDER BY n.fund_id, n.valuation_date DESC, n.nav_version_id DESC
      """;

  private final JdbcClient jdbc;

  public ReadOnlyFundNavRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
    return byFundAndDate(SELECT_ORIGINAL, fundId, valuationDate);
  }

  @Override
  public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
    return byFundAndDate(SELECT_LIVE_FOR_DATE, fundId, valuationDate);
  }

  @Override
  public Optional<FundNav> findLatestLive(FundId fundId) {
    return jdbc.sql(SELECT_LATEST_LIVE)
        .param("fundId", fundId.value())
        .query(ReadOnlyFundNavRepository::mapFundNav)
        .optional();
  }

  @Override
  public List<FundNav> findLatestLivePerFund() {
    return jdbc.sql(SELECT_LATEST_LIVE_PER_FUND)
        .query(ReadOnlyFundNavRepository::mapFundNav)
        .list();
  }

  @Override
  public FundNav strike(FundNav candidate) {
    throw new UnsupportedOperationException(
        "balance-api never strikes a NAV; valuation-api owns reference.fund_nav");
  }

  @Override
  public FundNav correct(FundNav candidate) {
    throw new UnsupportedOperationException(
        "balance-api never corrects a NAV; valuation-api owns reference.fund_nav");
  }

  private Optional<FundNav> byFundAndDate(String sql, FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(sql)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .query(ReadOnlyFundNavRepository::mapFundNav)
        .optional();
  }

  private static FundNav mapFundNav(ResultSet rs, int rowNum) throws SQLException {
    CurrencyCode currency = CurrencyCode.valueOf(rs.getString("base_currency"));
    Long supersedesId = (Long) rs.getObject("supersedes_id");
    return new FundNav(
        rs.getLong("nav_version_id"),
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getObject("valuation_date", LocalDate.class),
        Nav.of(rs.getLong("nav_scaled"), currency),
        Units.of(rs.getLong("units_in_issue")),
        rs.getString("source"),
        supersedesId,
        rs.getString("reason"),
        rs.getTimestamp("published_at").toInstant());
  }
}
