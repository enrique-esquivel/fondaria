package dev.fondaria.allotmentjob.plan;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PlanVersionDirectory} over {@code reference.plan_version}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>Resolution mirrors contribution-api's {@code JdbcPlanVersionRepository#resolveAsOf} exactly.
 * Duplicated deliberately rather than shared, matching every other read-only directory in this
 * service.
 *
 * <p>A row whose {@code fund_id} is {@code NULL} — published before ADR-0012's migration, or by a
 * caller that has not picked up the new field — resolves to nothing rather than a {@code
 * PlanVersion} this service cannot honestly construct: {@link PlanVersion}'s constructor requires a
 * non-null {@code fundId}, and a batch under such a plan version cannot be allotted until a
 * corrected version supplies one. That is an honest gap (Task 21.2 leaves the batch STAGED, retried
 * next sweep), not a wrong fund.
 */
@Repository
public class JdbcPlanVersionDirectory implements PlanVersionDirectory {

  private final JdbcClient jdbc;

  public JdbcPlanVersionDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
    // No `fund_id IS NOT NULL` filter: that would silently fall back to an OLDER version that
    // happens to carry one, breaking the "highest (effectiveFrom, version) not after the date"
    // resolution contract every other reader of this table relies on. Instead the winning row
    // is always read, and only then — in resolvedRow, below — is a null fund_id turned into
    // "nothing resolvable", never a quietly wrong (and older) plan version.
    return jdbc.sql(
            """
            SELECT organization_id, plan_id, effective_from, version,
                   employer_match_ratio_numerator, employer_match_ratio_denominator,
                   vesting_full_years, dealing_lag_business_days, fund_id, published_at
              FROM reference.plan_version
             WHERE organization_id = :organizationId
               AND plan_id = :planId
               AND effective_from <= :asOfDate
             ORDER BY effective_from DESC, version DESC
             LIMIT 1
            """)
        .param("organizationId", organizationId.value())
        .param("planId", planId)
        .param("asOfDate", asOfDate)
        .query(JdbcPlanVersionDirectory::mapRow)
        .optional()
        .flatMap(row -> row.fundId() == null ? Optional.empty() : Optional.of(row.toPlanVersion()));
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    UUID fundId = rs.getObject("fund_id", UUID.class);
    return new Row(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("plan_id"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getInt("version"),
        rs.getInt("employer_match_ratio_numerator"),
        rs.getInt("employer_match_ratio_denominator"),
        rs.getInt("vesting_full_years"),
        rs.getInt("dealing_lag_business_days"),
        fundId,
        rs.getTimestamp("published_at").toInstant());
  }

  private record Row(
      OrganizationId organizationId,
      String planId,
      LocalDate effectiveFrom,
      int version,
      int employerMatchRatioNumerator,
      int employerMatchRatioDenominator,
      int vestingFullYears,
      int dealingLagBusinessDays,
      UUID fundId,
      Instant publishedAt) {

    PlanVersion toPlanVersion() {
      return new PlanVersion(
          organizationId,
          planId,
          effectiveFrom,
          version,
          employerMatchRatioNumerator,
          employerMatchRatioDenominator,
          vestingFullYears,
          dealingLagBusinessDays,
          FundId.of(fundId),
          publishedAt);
    }
  }
}
