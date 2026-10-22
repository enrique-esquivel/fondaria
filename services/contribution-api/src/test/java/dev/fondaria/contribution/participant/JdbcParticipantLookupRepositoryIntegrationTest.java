package dev.fondaria.contribution.participant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationStatus;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The SQL behind {@link ParticipantLookupRepository}, against a real PostgreSQL — in particular
 * that it returns the {@code participant_id} the column default assigned, which {@link
 * ParticipantRepository} does not select at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcParticipantLookupRepositoryIntegrationTest extends ServiceIntegrationTest {

  private static final String PLAN_ID = "PLAN-ACME-CAJA-1";

  @Autowired private ParticipantLookupRepository lookups;
  @Autowired private ParticipantRepository participants;
  @Autowired private OrganizationRepository organizations;

  @Test
  @DisplayName("a participant enrolled by auto-enrolment resolves by internalRef")
  void resolvesByInternalRef() {
    OrganizationId organizationId = registerOrganization();
    enrol(organizationId, "P-001", "ACME-EMP-0142");

    ParticipantLookup lookup = lookups.findByInternalRef(organizationId, "P-001").orElseThrow();

    assertThat(lookup.internalRef()).isEqualTo("P-001");
    assertThat(lookup.payrollRef()).isEqualTo("ACME-EMP-0142");
    assertThat(lookup.participantId()).isNotNull();
  }

  @Test
  @DisplayName("the same participant resolves by payrollRef to the same participantId")
  void resolvesByPayrollRefToTheSameId() {
    OrganizationId organizationId = registerOrganization();
    enrol(organizationId, "P-001", "ACME-EMP-0142");

    ParticipantLookup byInternalRef =
        lookups.findByInternalRef(organizationId, "P-001").orElseThrow();
    ParticipantLookup byPayrollRef =
        lookups.findByPayrollRef(organizationId, "ACME-EMP-0142").orElseThrow();

    assertThat(byPayrollRef.participantId()).isEqualTo(byInternalRef.participantId());
  }

  @Test
  @DisplayName("an unknown reference resolves to nothing, not an error")
  void unknownReferenceResolvesToEmpty() {
    OrganizationId organizationId = registerOrganization();

    assertThat(lookups.findByInternalRef(organizationId, "P-404")).isEmpty();
    assertThat(lookups.findByPayrollRef(organizationId, "NOPE")).isEmpty();
  }

  private void enrol(OrganizationId organizationId, String internalRef, String payrollRef) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    participants.insertIfAbsent(
        new Participant(
            organizationId,
            internalRef,
            PLAN_ID,
            payrollRef,
            ParticipantStatus.ACTIVE,
            EmploymentStatus.ACTIVE,
            LocalDate.of(2019, 3, 1),
            LocalDate.of(2026, 1, 15),
            Optional.empty(),
            now,
            now));
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
