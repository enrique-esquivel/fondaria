package dev.fondaria.contribution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationStatus;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL and the schema behind {@link PlanVersionRepository}, against a real PostgreSQL — in
 * particular that {@code reference.plan_version} is append-only by grant (ADR-0011), not by
 * application discipline. What {@link PlanVersionRepository#resolveAsOf} decides — the highest
 * {@code (effectiveFrom, version)} not after the date asked for — is asserted without a database in
 * {@code MatchingRatioRuleTest} and {@code VestingScheduleCalculatorTest}; this proves the same SQL
 * against a real table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcPlanVersionRepositoryIntegrationTest extends ServiceIntegrationTest {

  private static final String PLAN_ID = "PLAN-ACME-CAJA-1";

  @Autowired private PlanVersionRepository repository;
  @Autowired private OrganizationRepository organizations;
  @Autowired private FundRepository funds;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("reference.plan_version grants SELECT and INSERT but never UPDATE or DELETE")
  void grantsAreAppendOnly() {
    assertThat(privilege("SELECT")).isTrue();
    assertThat(privilege("INSERT")).isTrue();
    assertThat(privilege("UPDATE")).isFalse();
    assertThat(privilege("DELETE")).isFalse();
  }

  @Test
  @DisplayName("resolving as-of a date returns the version in force then")
  void resolvesTheVersionInForceAsOfADate() {
    OrganizationId organizationId = registerOrganization();
    FundId fundId = registerFund();
    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2025, 1, 1), 1, 1, 1));
    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2026, 1, 1), 1, 1, 2));

    Optional<PlanVersion> resolvedEarly =
        repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2025, 6, 1));
    Optional<PlanVersion> resolvedLate =
        repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2026, 6, 1));

    assertThat(resolvedEarly)
        .hasValueSatisfying(v -> assertThat(v.employerMatchRatioDenominator()).isEqualTo(1));
    assertThat(resolvedLate)
        .hasValueSatisfying(v -> assertThat(v.employerMatchRatioDenominator()).isEqualTo(2));
  }

  @Test
  @DisplayName("publishing a later version does not change an already-evaluated resolution")
  void laterPublicationDoesNotChangeAPastResolution() {
    OrganizationId organizationId = registerOrganization();
    FundId fundId = registerFund();
    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2025, 1, 1), 1, 1, 1));

    Optional<PlanVersion> before =
        repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2025, 6, 1));

    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2026, 1, 1), 1, 1, 2));
    Optional<PlanVersion> after =
        repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2025, 6, 1));

    assertThat(after).isEqualTo(before);
  }

  @Test
  @DisplayName("a correction is a new version alongside the one it corrects, which stays readable")
  void aCorrectionIsANewVersionAlongsideTheOldOne() {
    OrganizationId organizationId = registerOrganization();
    FundId fundId = registerFund();
    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2026, 1, 1), 1, 1, 1));
    // The same effective date, corrected: version 2 must win resolution, and version 1 must
    // still exist as a row (asserted by reading it back with a raw query, since the port has
    // no "get by version" method — resolveAsOf only ever returns the winner).
    repository.publish(planVersion(organizationId, fundId, LocalDate.of(2026, 1, 1), 2, 1, 2));

    Optional<PlanVersion> resolved =
        repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2026, 6, 1));

    assertThat(resolved).hasValueSatisfying(v -> assertThat(v.version()).isEqualTo(2));
    Integer rowCount =
        jdbc.sql(
                "SELECT count(*) FROM reference.plan_version WHERE organization_id = :organizationId"
                    + " AND plan_id = :planId AND effective_from = :effectiveFrom")
            .param("organizationId", organizationId.value())
            .param("planId", PLAN_ID)
            .param("effectiveFrom", LocalDate.of(2026, 1, 1))
            .query(Integer.class)
            .single();
    assertThat(rowCount).isEqualTo(2);
  }

  @Test
  @DisplayName("no plan version resolves before any has been published")
  void noVersionResolvesBeforeAnyIsPublished() {
    OrganizationId organizationId = registerOrganization();

    assertThat(repository.resolveAsOf(organizationId, PLAN_ID, LocalDate.of(2026, 1, 1))).isEmpty();
  }

  private boolean privilege(String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', 'reference.plan_version', :privilege)")
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }

  private PlanVersion planVersion(
      OrganizationId organizationId,
      FundId fundId,
      LocalDate effectiveFrom,
      int version,
      int matchNumerator,
      int matchDenominator) {
    return new PlanVersion(
        organizationId,
        PLAN_ID,
        effectiveFrom,
        version,
        matchNumerator,
        matchDenominator,
        5,
        0,
        fundId,
        Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  private FundId registerFund() {
    FundId fundId = FundId.of(UUID.randomUUID());
    funds.publish(
        new Fund(
            fundId,
            LocalDate.of(2025, 1, 1),
            1,
            "Caja de Ahorro Acme",
            "CAJA_DE_AHORRO",
            CurrencyCode.MXN,
            ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2),
            Units.SCALE,
            LocalDate.of(2025, 1, 1),
            Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    return fundId;
  }

  private OrganizationId registerOrganization() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();
    OrganizationId organizationId = OrganizationId.derive(ORGANIZATION_NAMESPACE, taxId);
    organizations.insertIfAbsent(
        new Organization(
            organizationId,
            taxId,
            "Acme S.A. de C.V.",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            1,
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    return organizationId;
  }
}
