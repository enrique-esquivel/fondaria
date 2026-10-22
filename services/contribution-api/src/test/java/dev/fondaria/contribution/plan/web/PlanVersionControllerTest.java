package dev.fondaria.contribution.plan.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.fund.FundNotFoundException;
import dev.fondaria.contribution.fund.InMemoryFundRepository;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationRevision;
import dev.fondaria.contribution.organization.OrganizationStatus;
import dev.fondaria.contribution.plan.InMemoryPlanVersionRepository;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task D.0's plan-version acceptance criteria, at the level where the decisions are made. */
class PlanVersionControllerTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("publishing a plan version against a registered fund succeeds")
  void publishesAgainstARegisteredFund() {
    OrganizationId organizationId = registeredOrganizationId();
    KnownOrganizations organizations = new KnownOrganizations(Set.of(organizationId));
    FundId fundId = FundId.of(UUID.randomUUID());
    FundRepository funds = fundRepositoryWithOneFund(fundId);
    PlanVersionController controller =
        new PlanVersionController(new InMemoryPlanVersionRepository(), funds, organizations, CLOCK);

    ResponseEntity<PlanVersionResponse> response =
        controller.publish(organizationId.value().toString(), request(fundId, 1));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().fundId()).isEqualTo(fundId.toString());
  }

  @Test
  @DisplayName("replaying the identical plan version is a no-op 200")
  void replayIsANoOp() {
    OrganizationId organizationId = registeredOrganizationId();
    KnownOrganizations organizations = new KnownOrganizations(Set.of(organizationId));
    FundId fundId = FundId.of(UUID.randomUUID());
    FundRepository funds = fundRepositoryWithOneFund(fundId);
    PlanVersionController controller =
        new PlanVersionController(new InMemoryPlanVersionRepository(), funds, organizations, CLOCK);

    ResponseEntity<PlanVersionResponse> first =
        controller.publish(organizationId.value().toString(), request(fundId, 1));
    ResponseEntity<PlanVersionResponse> second =
        controller.publish(organizationId.value().toString(), request(fundId, 1));

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("the same (organizationId, planId, version) with different content is a conflict")
  void conflictingReplayIsRejected() {
    OrganizationId organizationId = registeredOrganizationId();
    KnownOrganizations organizations = new KnownOrganizations(Set.of(organizationId));
    FundId fundId = FundId.of(UUID.randomUUID());
    FundRepository funds = fundRepositoryWithOneFund(fundId);
    PlanVersionController controller =
        new PlanVersionController(new InMemoryPlanVersionRepository(), funds, organizations, CLOCK);
    controller.publish(organizationId.value().toString(), request(fundId, 1));

    PublishPlanVersionRequest differentVesting =
        new PublishPlanVersionRequest("PLAN-1", "2026-01-01", 1, 1, 2, 3, 0, fundId.toString());

    assertThatThrownBy(
            () -> controller.publish(organizationId.value().toString(), differentVesting))
        .isInstanceOf(PlanVersionConflictException.class);
  }

  @Test
  @DisplayName(
      "a fund that does not resolve as of effectiveFrom is a named 404, not a raw FK error")
  void unresolvedFundIsRejected() {
    OrganizationId organizationId = registeredOrganizationId();
    KnownOrganizations organizations = new KnownOrganizations(Set.of(organizationId));
    PlanVersionController controller =
        new PlanVersionController(
            new InMemoryPlanVersionRepository(),
            new InMemoryFundRepository(),
            organizations,
            CLOCK);

    assertThatThrownBy(
            () ->
                controller.publish(
                    organizationId.value().toString(), request(FundId.of(UUID.randomUUID()), 1)))
        .isInstanceOf(FundNotFoundException.class);
  }

  @Test
  @DisplayName("a constraint violation the as-of read could not foresee is a conflict, not a 500")
  void unforeseenConstraintViolationIsAConflictNotAServerError() {
    OrganizationId organizationId = registeredOrganizationId();
    KnownOrganizations organizations = new KnownOrganizations(Set.of(organizationId));
    FundId fundId = FundId.of(UUID.randomUUID());
    FundRepository funds = fundRepositoryWithOneFund(fundId);
    PlanVersionController controller =
        new PlanVersionController(
            new AlwaysConflictingPlanVersionRepository(), funds, organizations, CLOCK);

    assertThatThrownBy(
            () -> controller.publish(organizationId.value().toString(), request(fundId, 1)))
        .isInstanceOf(PlanVersionConflictException.class);
  }

  @Test
  @DisplayName("an unregistered organization is a named 404")
  void unknownOrganizationIsRejected() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FundRepository funds = fundRepositoryWithOneFund(fundId);
    PlanVersionController controller =
        new PlanVersionController(
            new InMemoryPlanVersionRepository(), funds, new KnownOrganizations(Set.of()), CLOCK);

    assertThatThrownBy(() -> controller.publish(UUID.randomUUID().toString(), request(fundId, 1)))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  private static OrganizationId registeredOrganizationId() {
    return new OrganizationId(UUID.randomUUID());
  }

  private static PublishPlanVersionRequest request(FundId fundId, int version) {
    return new PublishPlanVersionRequest(
        "PLAN-1", "2026-01-01", version, 1, 2, 5, 0, fundId.toString());
  }

  private static FundRepository fundRepositoryWithOneFund(FundId fundId) {
    InMemoryFundRepository funds = new InMemoryFundRepository();
    funds.publish(
        new Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Caja de Ahorro Acme",
            "CAJA_DE_AHORRO",
            CurrencyCode.MXN,
            1_001L,
            Units.SCALE,
            LocalDate.of(2020, 1, 1),
            Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.parse("2020-01-01T00:00:00Z")));
    return funds;
  }

  /** A minimal {@link OrganizationRepository} fake: only {@code findById} is exercised here. */
  private static final class KnownOrganizations implements OrganizationRepository {

    private final Map<OrganizationId, Organization> known = new HashMap<>();

    private KnownOrganizations(Set<OrganizationId> knownIds) {
      Instant now = Instant.parse("2020-01-01T00:00:00Z");
      for (OrganizationId id : knownIds) {
        known.put(
            id,
            new Organization(
                id,
                TaxIdentifier.of(TaxIdScheme.MX_RFC, FreshTaxIdentifiers.freshRfc()),
                "Acme",
                Optional.empty(),
                Optional.empty(),
                OrganizationStatus.ACTIVE,
                1,
                now,
                now));
      }
    }

    @Override
    public boolean insertIfAbsent(Organization organization) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void update(Organization next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Organization> findById(OrganizationId organizationId) {
      return Optional.ofNullable(known.get(organizationId));
    }

    @Override
    public List<Organization> findAll(int limit, int offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<OrganizationRevision> findRevisions(OrganizationId organizationId) {
      throw new UnsupportedOperationException();
    }
  }

  /** Always fails as if the primary key were already taken, however this call resolved. */
  private static final class AlwaysConflictingPlanVersionRepository
      implements PlanVersionRepository {

    @Override
    public Optional<PlanVersion> resolveAsOf(
        OrganizationId organizationId, String planId, LocalDate asOfDate) {
      return Optional.empty();
    }

    @Override
    public PlanVersion publish(PlanVersion candidate) {
      throw new DataIntegrityViolationException(
          "duplicate key value violates unique constraint \"plan_version_pk\"");
    }
  }
}
