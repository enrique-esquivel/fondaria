package dev.fondaria.contribution.plan;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PlanVersionRepository} over {@code reference.plan_version}, using {@code JdbcClient}
 * (AGENTS.md §5 — no ORM). Append-only by grant, not by application logic (ADR-0011): {@code
 * reference}'s schema-level default privileges never grant {@code UPDATE}/{@code DELETE}, so {@link
 * #publish} is always an {@code INSERT}.
 */
@Repository
public class JdbcPlanVersionRepository implements PlanVersionRepository {

  private final JdbcClient jdbc;

  public JdbcPlanVersionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
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
        .query(JdbcPlanVersionRepository::mapPlanVersion)
        .optional();
  }

  @Override
  public PlanVersion publish(PlanVersion candidate) {
    jdbc.sql(
            """
            INSERT INTO reference.plan_version (
                organization_id, plan_id, effective_from, version,
                employer_match_ratio_numerator, employer_match_ratio_denominator,
                vesting_full_years, dealing_lag_business_days, fund_id, published_at)
            VALUES (:organizationId, :planId, :effectiveFrom, :version,
                    :employerMatchRatioNumerator, :employerMatchRatioDenominator,
                    :vestingFullYears, :dealingLagBusinessDays, :fundId, :publishedAt)
            """)
        .param("organizationId", candidate.organizationId().value())
        .param("planId", candidate.planId())
        .param("effectiveFrom", candidate.effectiveFrom())
        .param("version", candidate.version())
        .param("employerMatchRatioNumerator", candidate.employerMatchRatioNumerator())
        .param("employerMatchRatioDenominator", candidate.employerMatchRatioDenominator())
        .param("vestingFullYears", candidate.vestingFullYears())
        .param("dealingLagBusinessDays", candidate.dealingLagBusinessDays())
        .param("fundId", candidate.fundId().value())
        .param("publishedAt", Timestamp.from(candidate.publishedAt()))
        .update();
    return candidate;
  }

  private static PlanVersion mapPlanVersion(ResultSet rs, int rowNum) throws SQLException {
    return new PlanVersion(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("plan_id"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getInt("version"),
        rs.getInt("employer_match_ratio_numerator"),
        rs.getInt("employer_match_ratio_denominator"),
        rs.getInt("vesting_full_years"),
        rs.getInt("dealing_lag_business_days"),
        FundId.of(rs.getObject("fund_id", UUID.class)),
        rs.getTimestamp("published_at").toInstant());
  }
}
