package dev.fondaria.contribution.fund;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FundRepository} over {@code reference.fund_identity} and {@code reference.fund}, using
 * {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@link #publish} writes both tables in one transaction: the identity insert is {@code ON
 * CONFLICT DO NOTHING}, so the first call for a {@code fundId} registers it and every later call is
 * a no-op on that table, while the version insert is always a fresh append — mirroring {@code
 * JdbcPlanVersionRepository}, which is always an {@code INSERT} because {@code reference}'s
 * schema-level default privileges never grant {@code UPDATE}/{@code DELETE}.
 */
@Repository
public class JdbcFundRepository implements FundRepository {

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

  public JdbcFundRepository(JdbcClient jdbc) {
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
        .query(JdbcFundRepository::mapFund)
        .optional();
  }

  @Override
  @Transactional
  public Fund publish(Fund candidate) {
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, :baseCurrency, :unitScale, :inceptionDate,
                    :openingNavScaled, :createdAt)
            ON CONFLICT (fund_id) DO NOTHING
            """)
        .param("fundId", candidate.fundId().value())
        .param("unitLedgerId", candidate.unitLedgerId())
        .param("baseCurrency", candidate.baseCurrency().name())
        .param("unitScale", candidate.unitScale())
        .param("inceptionDate", candidate.inceptionDate())
        .param("openingNavScaled", candidate.openingNav().scaled())
        .param("createdAt", Timestamp.from(candidate.publishedAt()))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, :version, :name, :vehicleType, :status, :publishedAt)
            """)
        .param("fundId", candidate.fundId().value())
        .param("effectiveFrom", candidate.effectiveFrom())
        .param("version", candidate.version())
        .param("name", candidate.name())
        .param("vehicleType", candidate.vehicleType())
        .param("status", candidate.status().name())
        .param("publishedAt", Timestamp.from(candidate.publishedAt()))
        .update();
    return candidate;
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
