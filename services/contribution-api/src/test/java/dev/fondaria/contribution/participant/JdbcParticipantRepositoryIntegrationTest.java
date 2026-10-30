package dev.fondaria.contribution.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL and the schema behind {@link ParticipantRepository}, against a real PostgreSQL. What the
 * service <i>decides</i> is asserted without a database in {@code ParticipantEnrolmentServiceTest};
 * what the database <i>enforces</i> — the two unique constraints and the date ordering {@code
 * CHECK} — can only be asserted here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcParticipantRepositoryIntegrationTest extends ServiceIntegrationTest {

  private static final String PLAN_ID = "PLAN-ACME-CAJA-1";

  @Autowired private ParticipantRepository repository;
  @Autowired private OrganizationRepository organizations;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("master.participant carries the grants a mutable master-schema table has")
  void grantsMatchTheMasterSchema() {
    assertThat(privilege("master.participant", "SELECT")).isTrue();
    assertThat(privilege("master.participant", "INSERT")).isTrue();
    assertThat(privilege("master.participant", "UPDATE")).isTrue();
  }

  @Test
  @DisplayName("insertIfAbsent is conditional: a second call for the same participant is a no-op")
  void insertIsConditional() {
    OrganizationId organizationId = registerOrganization();
    Participant participant = participant(organizationId, "P-001", "ACME-EMP-0142");

    assertThat(repository.insertIfAbsent(participant)).isTrue();
    assertThat(repository.insertIfAbsent(participant)).isFalse();

    assertThat(repository.find(organizationId, "P-001"))
        .hasValueSatisfying(stored -> assertThat(stored.payrollRef()).isEqualTo("ACME-EMP-0142"));
  }

  @Test
  @DisplayName("the two employment dates round-trip through PostgreSQL distinctly")
  void employmentDatesRoundTripDistinctly() {
    OrganizationId organizationId = registerOrganization();
    LocalDate employmentStart = LocalDate.of(2019, 3, 1);
    LocalDate enrolment = LocalDate.of(2026, 1, 15);
    repository.insertIfAbsent(
        new Participant(
            organizationId,
            "P-001",
            PLAN_ID,
            "ACME-EMP-0142",
            ParticipantStatus.ACTIVE,
            EmploymentStatus.ACTIVE,
            employmentStart,
            enrolment,
            Optional.empty(),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS)));

    Participant stored = repository.find(organizationId, "P-001").orElseThrow();

    // The data step 5's vesting assertion is computed from (BACKLOG.md, "4b still owes").
    assertThat(stored.employmentStartDate()).isEqualTo(employmentStart);
    assertThat(stored.enrolmentDate()).isEqualTo(enrolment);
    assertThat(stored.employmentStartDate()).isNotEqualTo(stored.enrolmentDate());
  }

  @Test
  @DisplayName("two organizations may each enrol a participant carrying the same payroll ref")
  void payrollRefIsUniqueOnlyWithinAnOrganization() {
    OrganizationId first = registerOrganization();
    OrganizationId second = registerOrganization();

    assertThat(repository.insertIfAbsent(participant(first, "P-100", "E-001"))).isTrue();
    assertThat(repository.insertIfAbsent(participant(second, "P-200", "E-001"))).isTrue();

    assertThat(repository.find(first, "P-100"))
        .hasValueSatisfying(p -> assertThat(p.payrollRef()).isEqualTo("E-001"));
    assertThat(repository.find(second, "P-200"))
        .hasValueSatisfying(p -> assertThat(p.payrollRef()).isEqualTo("E-001"));
  }

  @Test
  @DisplayName(
      "insertAllIfAbsent spans several statements and counts only the participants it created")
  void insertAllIsConditionalPerRowAcrossStatements() {
    OrganizationId organizationId = registerOrganization();
    int size = JdbcParticipantRepository.ROWS_PER_STATEMENT * 2 + 7;
    List<Participant> participants =
        IntStream.range(0, size)
            .mapToObj(
                i ->
                    participant(
                        organizationId, String.format("P-%06d", i), String.format("E-%06d", i)))
            .toList();
    Participant extra = participant(organizationId, "P-EXTRA", "E-EXTRA");
    List<Participant> again = new ArrayList<>(participants);
    again.add(extra);

    assertThat(repository.insertAllIfAbsent(participants.subList(0, 10))).isEqualTo(10);
    assertThat(repository.insertAllIfAbsent(participants)).isEqualTo(size - 10);
    assertThat(repository.insertAllIfAbsent(again)).isEqualTo(1);

    assertThat(participantCount(organizationId)).isEqualTo(size + 1);
    assertThat(repository.find(organizationId, String.format("P-%06d", size - 1)))
        .hasValueSatisfying(
            p -> {
              assertThat(p.payrollRef()).isEqualTo(String.format("E-%06d", size - 1));
              assertThat(p.employmentEndDate()).isEmpty();
            });
  }

  @Test
  @DisplayName("a payroll-ref clash fails its whole statement: none of that statement's rows land")
  void aPayrollRefClashFailsTheWholeStatement() {
    OrganizationId organizationId = registerOrganization();
    repository.insertIfAbsent(participant(organizationId, "P-001", "E-001"));

    assertThatThrownBy(
            () ->
                repository.insertAllIfAbsent(
                    List.of(
                        participant(organizationId, "P-002", "E-002"),
                        participant(organizationId, "P-003", "E-001"))))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(repository.find(organizationId, "P-002")).isEmpty();
    assertThat(participantCount(organizationId)).isEqualTo(1);
  }

  @Test
  @DisplayName("the same payroll ref twice within one organization violates the unique constraint")
  void payrollRefIsUniqueWithinAnOrganization() {
    OrganizationId organizationId = registerOrganization();
    repository.insertIfAbsent(participant(organizationId, "P-001", "E-001"));

    assertThatThrownBy(
            () -> repository.insertIfAbsent(participant(organizationId, "P-002", "E-001")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an enrolment date before the employment start date violates the CHECK constraint")
  void enrolmentBeforeEmploymentStartIsRejectedByTheDatabase() {
    OrganizationId organizationId = registerOrganization();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO master.participant (
                            organization_id, participant_internal_ref, plan_id,
                            participant_payroll_ref, participant_status, employment_status,
                            employment_start_date, enrolment_date, created_at, updated_at)
                        VALUES (:organizationId, 'P-BAD', :planId, 'E-BAD', 'ACTIVE', 'ACTIVE',
                                '2026-01-15', '2019-03-01', now(), now())
                        """)
                    .param("organizationId", organizationId.value())
                    .param("planId", PLAN_ID)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private long participantCount(OrganizationId organizationId) {
    return jdbc.sql("SELECT count(*) FROM master.participant WHERE organization_id = :id")
        .param("id", organizationId.value())
        .query(Long.class)
        .single();
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }

  private Participant participant(
      OrganizationId organizationId, String internalRef, String payrollRef) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return new Participant(
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
        now);
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
