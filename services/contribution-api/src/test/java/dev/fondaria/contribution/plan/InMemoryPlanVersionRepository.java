package dev.fondaria.contribution.plan;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link PlanVersionRepository}, so what {@code ContributionBatchValidator} decides —
 * resolving the version in force as-of a date, and rejecting when none does — is provable without a
 * database in the loop. The one JDBC implementation proves the SQL separately, against a real
 * PostgreSQL, in {@code JdbcPlanVersionRepositoryIntegrationTest}.
 *
 * <p>Public rather than package-private: {@code ContributionBatchValidatorTest} and {@code
 * BatchValidationRunnerTest} both need it.
 */
public final class InMemoryPlanVersionRepository implements PlanVersionRepository {

  private final List<PlanVersion> versions = new ArrayList<>();

  @Override
  public Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
    return versions.stream()
        .filter(v -> v.organizationId().equals(organizationId))
        .filter(v -> v.planId().equals(planId))
        .filter(v -> !v.effectiveFrom().isAfter(asOfDate))
        .max(Comparator.comparing(PlanVersion::effectiveFrom).thenComparing(PlanVersion::version));
  }

  @Override
  public PlanVersion publish(PlanVersion candidate) {
    versions.add(candidate);
    return candidate;
  }
}
