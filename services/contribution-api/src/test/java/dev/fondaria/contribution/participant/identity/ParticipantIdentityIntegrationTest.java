package dev.fondaria.contribution.participant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.fondaria.contribution.participant.EmploymentStatus;
import dev.fondaria.contribution.participant.Participant;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.contribution.participant.ParticipantStatus;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 9.5's crypto-shredding, proved against a real PostgreSQL: what {@link
 * ParticipantIdentityServiceTest} proves with in-memory fakes, this proves is actually true of
 * {@code master.participant_identity} and its grants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ParticipantIdentityIntegrationTest extends ServiceIntegrationTest {

  @Autowired private ParticipantIdentityService service;
  @Autowired private ParticipantIdentityRepository identityRepository;
  @Autowired private ParticipantRepository participants;
  @Autowired private OrganizationRepository organizations;
  @Autowired private PlanVersionRepository planVersions;
  @Autowired private FundRepository funds;
  @Autowired private Clock clock;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("master.participant_identity carries the grants a mutable master-schema table has")
  void grantsMatchTheMasterSchema() {
    assertThat(privilege("master.participant_identity", "SELECT")).isTrue();
    assertThat(privilege("master.participant_identity", "INSERT")).isTrue();
    assertThat(privilege("master.participant_identity", "UPDATE")).isTrue();
    // No DELETE: a row is corrected by resubmission and erased by nulling wrapped_data_key
    // (key_destroyed_at records when), never by removing the row -- that column is the only
    // evidence a crypto-shred ever happened.
    assertThat(privilege("master.participant_identity", "DELETE")).isFalse();
  }

  @Test
  @DisplayName("what is persisted in PostgreSQL is ciphertext, never the plaintext identity value")
  void persistedFieldsAreCiphertext() {
    OrganizationId org = registerOrganization();
    String ref = enrol(org, "PLAN-CAJA");
    registerPlan(org, "PLAN-CAJA", registerFund(org, "CAJA_DE_AHORRO"));

    service.captureIdentity(
        org,
        ref,
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    String rawRfc =
        jdbc.sql(
                "SELECT encode(encrypted_rfc, 'escape') FROM master.participant_identity"
                    + " WHERE organization_id = :organizationId AND participant_internal_ref = :ref")
            .param("organizationId", org.value())
            .param("ref", ref)
            .query(String.class)
            .single();
    assertThat(rawRfc).doesNotContain("XAXX010101000");

    DecryptedIdentity decrypted = service.readIdentity(org, ref);
    assertThat(decrypted.rfc()).isEqualTo("XAXX010101000");
    assertThat(decrypted.name()).isEqualTo("Juan Perez");
  }

  @Test
  @DisplayName(
      "crypto-shredding one participant's key leaves master.participant and every other"
          + " participant's identity untouched")
  void cryptoShreddingIsIsolatedToOneParticipant() {
    OrganizationId org = registerOrganization();
    FundId cajaFund = registerFund(org, "CAJA_DE_AHORRO");
    registerPlan(org, "PLAN-CAJA", cajaFund);
    String shredded = enrol(org, "PLAN-CAJA");
    String untouched = enrol(org, "PLAN-CAJA");

    service.captureIdentity(
        org,
        shredded,
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));
    service.captureIdentity(
        org,
        untouched,
        new IdentityCaptureCommand(
            "XEXX010101000", "WXYZ900101HDFRRN02", "Maria Gonzalez", Optional.empty()));

    Participant beforeShred = participants.find(org, shredded).orElseThrow();

    service.destroyKey(org, shredded);

    // The shredded participant's own master.participant row -- employment/participant status,
    // both employment dates -- is untouched. This table has no foreign key from any ledger or
    // participant_units row, and none of this test's writes touch either: crypto-shredding a data
    // key structurally cannot reach a balance or a position, which is exactly why it is a
    // companion table rather than columns added to master.participant itself.
    Participant afterShred = participants.find(org, shredded).orElseThrow();
    assertThat(afterShred).isEqualTo(beforeShred);

    assertThatThrownBy(() -> service.readIdentity(org, shredded))
        .isInstanceOf(IdentityKeyDestroyedException.class);

    DecryptedIdentity stillReadable = service.readIdentity(org, untouched);
    assertThat(stillReadable.rfc()).isEqualTo("XEXX010101000");
    assertThat(stillReadable.name()).isEqualTo("Maria Gonzalez");
  }

  @Test
  @DisplayName("a terminated employee's identity remains captured and readable")
  void terminatedEmployeeIdentityRemainsReadable() {
    // Task 2b.5 (the termination/rehire lifecycle itself) is unimplemented and out of this task's
    // scope (BACKLOG.md "No leaver in the demo"; ParticipantRepository has no update method at
    // all). What Task 9.5's third acceptance criterion asks this session to prove is the
    // structural property Task 2b.5 will rely on: that employment_status and participant_status
    // are independent columns, and that nothing in the identity feature reads employment_status to
    // gate access. Proved here the way Task 2b.5's own eventual transition will write it -- a
    // direct UPDATE -- rather than by building that service now.
    OrganizationId org = registerOrganization();
    FundId cajaFund = registerFund(org, "CAJA_DE_AHORRO");
    registerPlan(org, "PLAN-CAJA", cajaFund);
    String ref = enrol(org, "PLAN-CAJA");

    service.captureIdentity(
        org,
        ref,
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    jdbc.sql(
            "UPDATE master.participant SET employment_status = 'TERMINATED',"
                + " employment_end_date = :endDate"
                + " WHERE organization_id = :organizationId AND participant_internal_ref = :ref")
        .param("endDate", LocalDate.now(clock))
        .param("organizationId", org.value())
        .param("ref", ref)
        .update();

    Participant terminated = participants.find(org, ref).orElseThrow();
    assertThat(terminated.employmentStatus()).isEqualTo(EmploymentStatus.TERMINATED);
    // The two lifecycles are independent (phase-02b): leaving the employer does not close the
    // participant's own standing with the platform.
    assertThat(terminated.status()).isEqualTo(ParticipantStatus.ACTIVE);

    // Identity remains captured and decryptable -- nothing in ParticipantIdentityService consults
    // employmentStatus, so a terminated employee's identity is exactly as readable as before.
    DecryptedIdentity identity = service.readIdentity(org, ref);
    assertThat(identity.rfc()).isEqualTo("XAXX010101000");

    // Capture/update still succeeds for a terminated employee -- correcting a mistyped field after
    // termination is not blocked, matching "can still authenticate": nothing here, or in Keycloak's
    // token issuance, consults master.participant.employment_status at all.
    service.captureIdentity(
        org,
        ref,
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez Corrected", Optional.empty()));
    assertThat(service.readIdentity(org, ref).name()).isEqualTo("Juan Perez Corrected");
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
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

  private String enrol(OrganizationId organizationId, String planId) {
    String internalRef = "P-" + UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    participants.insertIfAbsent(
        new Participant(
            organizationId,
            internalRef,
            planId,
            "PAYROLL-" + internalRef,
            ParticipantStatus.ACTIVE,
            EmploymentStatus.ACTIVE,
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2020, 1, 1),
            Optional.empty(),
            now,
            now));
    return internalRef;
  }

  private FundId registerFund(OrganizationId organizationId, String vehicleType) {
    FundId fundId = FundId.of(UUID.randomUUID());
    funds.publish(
        new Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Fund " + vehicleType + " " + organizationId,
            vehicleType,
            CurrencyCode.MXN,
            freshLedgerId(),
            Units.SCALE,
            LocalDate.of(2020, 1, 1),
            Nav.of(100_000_000L, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.now()));
    return fundId;
  }

  private void registerPlan(OrganizationId organizationId, String planId, FundId fundId) {
    planVersions.publish(
        new PlanVersion(
            organizationId,
            planId,
            LocalDate.of(2020, 1, 1),
            1,
            1,
            1,
            0,
            0,
            fundId,
            Instant.now()));
  }

  private static long freshLedgerId() {
    return java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
