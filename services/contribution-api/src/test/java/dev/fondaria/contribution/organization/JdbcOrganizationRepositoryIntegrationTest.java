package dev.fondaria.contribution.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL behind {@link OrganizationRepository}, and the schema layout ADR-0011 requires, against a
 * real PostgreSQL. What the service <i>decides</i> is asserted without a database in {@code
 * OrganizationServiceTest}; what the database <i>enforces</i> can only be asserted here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcOrganizationRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private OrganizationRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("ADR-0011's four schemas exist")
  void fourSchemasExist() {
    List<String> schemas =
        jdbc.sql(
                """
                SELECT nspname FROM pg_namespace
                 WHERE nspname IN ('projection', 'master', 'reference', 'workflow')
                 ORDER BY nspname
                """)
            .query(String.class)
            .list();

    assertThat(schemas).containsExactly("master", "projection", "reference", "workflow");
  }

  @Test
  @DisplayName(
      "the application role may update master, but never rewrite an organization's history")
  void historyIsAppendOnlyByGrant() {
    assertThat(privilege("master.organization", "UPDATE")).isTrue();
    assertThat(privilege("master.organization", "INSERT")).isTrue();
    assertThat(privilege("master.organization_revision", "INSERT")).isTrue();
    assertThat(privilege("master.organization_revision", "SELECT")).isTrue();
    assertThat(privilege("master.organization_revision", "UPDATE"))
        .as("a correction is a new revision, never an edit")
        .isFalse();
    assertThat(privilege("master.organization_revision", "DELETE")).isFalse();
  }

  @Test
  @DisplayName("insertIfAbsent is conditional, and writes the first revision with the row")
  void insertIsConditional() {
    Organization organization = organization(FreshTaxIdentifiers.fresh(), "Acme");

    assertThat(repository.insertIfAbsent(organization)).isTrue();
    assertThat(repository.insertIfAbsent(organization)).isFalse();

    assertThat(repository.findById(organization.organizationId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.legalName()).isEqualTo("Acme");
              assertThat(stored.revision()).isEqualTo(1);
              assertThat(stored.status()).isEqualTo(OrganizationStatus.ACTIVE);
              assertThat(stored.contractReference()).contains("CT-2026-0001");
            });
    assertThat(repository.findRevisions(organization.organizationId()))
        .extracting(OrganizationRevision::revision)
        .containsExactly(1);
  }

  @Test
  @DisplayName("a second organization may not claim an already-registered tax identifier")
  void taxIdentifierIsUniqueAcrossOrganizations() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();
    repository.insertIfAbsent(organization(taxId, "Acme"));

    // A different id carrying the same tax identifier can only come from a
    // changed derivation namespace — which must not be able to split one
    // employer's money across two organizations unnoticed.
    Organization impostor =
        new Organization(
            new OrganizationId(UUID.randomUUID()),
            taxId,
            "Acme Clone",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            1,
            Instant.now(),
            Instant.now());

    assertThatThrownBy(() -> repository.insertIfAbsent(impostor))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an update replaces current state and appends the next revision")
  void updateAppendsRevision() {
    Organization registered = organization(FreshTaxIdentifiers.fresh(), "Acme");
    repository.insertIfAbsent(registered);

    Organization next =
        new OrganizationChange(
                Optional.of("Acme Servicios"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(OrganizationStatus.SUSPENDED))
            .applyTo(registered, registered.updatedAt().plusSeconds(60));
    repository.update(next);

    assertThat(repository.findById(registered.organizationId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.legalName()).isEqualTo("Acme Servicios");
              assertThat(stored.status()).isEqualTo(OrganizationStatus.SUSPENDED);
              assertThat(stored.revision()).isEqualTo(2);
              assertThat(stored.createdAt()).isEqualTo(registered.createdAt());
            });
    assertThat(repository.findRevisions(registered.organizationId()))
        .extracting(OrganizationRevision::legalName)
        .containsExactly("Acme", "Acme Servicios");
  }

  @Test
  @DisplayName("an update against a stale revision is refused rather than silently applied")
  void staleUpdateIsRefused() {
    Organization registered = organization(FreshTaxIdentifiers.fresh(), "Acme");
    repository.insertIfAbsent(registered);

    Organization stale =
        new Organization(
            registered.organizationId(),
            registered.taxIdentifier(),
            "Acme Stale",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            5,
            registered.createdAt(),
            registered.updatedAt());

    assertThatThrownBy(() -> repository.update(stale)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("updating an organization that does not exist is not found, not a silent no-op")
  void updatingAnUnknownOrganizationIsNotFound() {
    Organization unknown =
        new Organization(
            new OrganizationId(UUID.randomUUID()),
            FreshTaxIdentifiers.fresh(),
            "Ghost",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            2,
            Instant.now(),
            Instant.now());

    assertThatThrownBy(() -> repository.update(unknown))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  @DisplayName("a listed organization carries its revision, and the list is stably ordered")
  void listCarriesRevisionAndIsOrdered() {
    Organization first = organization(FreshTaxIdentifiers.fresh(), "First");
    repository.insertIfAbsent(first);
    Organization second =
        organizationAt(FreshTaxIdentifiers.fresh(), "Second", first.createdAt().plusSeconds(1));
    repository.insertIfAbsent(second);
    repository.update(
        new OrganizationChange(
                Optional.of("Second Renamed"), Optional.empty(), Optional.empty(), Optional.empty())
            .applyTo(second, second.updatedAt().plusSeconds(30)));

    List<Organization> listed = repository.findAll(500, 0);

    // Only the two this test created are asserted over: the fixture is reused
    // across runs, so a total over the table would be a previous run's number.
    List<Organization> mine =
        listed.stream()
            .filter(
                organization ->
                    organization.organizationId().equals(first.organizationId())
                        || organization.organizationId().equals(second.organizationId()))
            .toList();
    assertThat(mine).extracting(Organization::legalName).containsExactly("First", "Second Renamed");
    assertThat(mine).extracting(Organization::revision).containsExactly(1, 2);
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }

  private static Organization organization(TaxIdentifier taxId, String legalName) {
    return organizationAt(taxId, legalName, Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  private static Organization organizationAt(TaxIdentifier taxId, String legalName, Instant at) {
    return new Organization(
        OrganizationId.derive(ORGANIZATION_NAMESPACE, taxId),
        taxId,
        legalName,
        Optional.of("CT-2026-0001"),
        Optional.of("BRACKET_A"),
        OrganizationStatus.ACTIVE,
        1,
        at,
        at);
  }
}
