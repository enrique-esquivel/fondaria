package dev.fondaria.contribution.participant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.fund.InMemoryFundRepository;
import dev.fondaria.contribution.participant.EmploymentStatus;
import dev.fondaria.contribution.participant.Participant;
import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.contribution.participant.ParticipantStatus;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ParticipantIdentityService}'s decisions, provable without a database: whether date of
 * birth is required, that capture round-trips through encryption, and that crypto-shredding one
 * participant leaves another's identity readable. What PostgreSQL itself enforces is asserted
 * separately in {@code ParticipantIdentityIntegrationTest}.
 */
class ParticipantIdentityServiceTest {

  private static final OrganizationId ORG =
      new OrganizationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private InMemoryParticipantRepository participants;
  private InMemoryParticipantIdentityRepository identities;
  private PlanVersionRepository planVersions;
  private FundRepository funds;
  private ParticipantIdentityService service;

  @BeforeEach
  void setUp() {
    participants = new InMemoryParticipantRepository();
    identities = new InMemoryParticipantIdentityRepository();
    planVersions = new InMemoryPlanVersionRepository();
    funds = new InMemoryFundRepository();
    ParticipantIdentityMasterKey masterKey =
        new ParticipantIdentityMasterKey(new IdentityCipher().generateDataKey());
    service =
        new ParticipantIdentityService(
            participants, identities, planVersions, funds, masterKey, CLOCK);

    enrol("caja-participant", "PLAN-CAJA");
    enrol("retirement-participant", "PLAN-RETIREMENT");
    registerPlan("PLAN-CAJA", registerFund("CAJA_DE_AHORRO"));
    registerPlan("PLAN-RETIREMENT", registerFund("RETIREMENT_FUND"));
  }

  @Test
  @DisplayName("captured identity round-trips through encryption exactly")
  void captureThenReadRoundTrips() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    DecryptedIdentity identity = service.readIdentity(ORG, "caja-participant");

    assertThat(identity.rfc()).isEqualTo("XAXX010101000");
    assertThat(identity.curp()).isEqualTo("ABCD900101HDFRRN01");
    assertThat(identity.name()).isEqualTo("Juan Perez");
    assertThat(identity.dateOfBirth()).isEmpty();
  }

  @Test
  @DisplayName("what is stored is never the plaintext")
  void storedFieldsAreCiphertextNotPlaintext() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    ParticipantIdentity stored = identities.find(ORG, "caja-participant").orElseThrow();
    assertThat(new String(stored.encryptedRfc(), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("XAXX010101000");
    assertThat(new String(stored.encryptedName(), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("Juan Perez");
  }

  @Test
  @DisplayName("date of birth is required when the plan resolves to a retirement vehicle")
  void dateOfBirthRequiredForRetirementVehicle() {
    assertThatThrownBy(
            () ->
                service.captureIdentity(
                    ORG,
                    "retirement-participant",
                    new IdentityCaptureCommand(
                        "XAXX010101000", "ABCD900101HDFRRN01", "Maria Gonzalez", Optional.empty())))
        .isInstanceOf(InvalidIdentityCaptureRequestException.class);

    service.captureIdentity(
        ORG,
        "retirement-participant",
        new IdentityCaptureCommand(
            "XAXX010101000",
            "ABCD900101HDFRRN01",
            "Maria Gonzalez",
            Optional.of(LocalDate.of(1990, 5, 14))));

    assertThat(service.readIdentity(ORG, "retirement-participant").dateOfBirth())
        .contains(LocalDate.of(1990, 5, 14));
  }

  @Test
  @DisplayName("date of birth is optional for a caja de ahorro vehicle")
  void dateOfBirthOptionalForCajaVehicle() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    assertThat(service.readIdentity(ORG, "caja-participant").dateOfBirth()).isEmpty();
  }

  @Test
  @DisplayName("a blank required field is rejected before any key is generated")
  void blankRequiredFieldIsRejected() {
    assertThatThrownBy(
            () ->
                service.captureIdentity(
                    ORG,
                    "caja-participant",
                    new IdentityCaptureCommand(
                        "  ", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty())))
        .isInstanceOf(InvalidIdentityCaptureRequestException.class);
    assertThat(identities.find(ORG, "caja-participant")).isEmpty();
  }

  @Test
  @DisplayName("capturing identity for a participant that does not exist is rejected")
  void unknownParticipantIsRejected() {
    assertThatThrownBy(
            () ->
                service.captureIdentity(
                    ORG,
                    "no-such-participant",
                    new IdentityCaptureCommand(
                        "XAXX010101000", "ABCD900101HDFRRN01", "X", Optional.empty())))
        .isInstanceOf(ParticipantNotFoundException.class);
  }

  @Test
  @DisplayName("destroying a key makes that participant's identity permanently unreadable")
  void destroyKeyMakesIdentityUnreadable() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    service.destroyKey(ORG, "caja-participant");

    assertThatThrownBy(() -> service.readIdentity(ORG, "caja-participant"))
        .isInstanceOf(IdentityKeyDestroyedException.class);
    ParticipantIdentity stored = identities.find(ORG, "caja-participant").orElseThrow();
    assertThat(stored.wrappedDataKey()).isEmpty();
    assertThat(stored.keyDestroyedAt()).isPresent();
  }

  @Test
  @DisplayName("capturing over an already-destroyed identity is refused, not silently resurrected")
  void captureRefusesToResurrectAShreddedIdentity() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));
    service.destroyKey(ORG, "caja-participant");

    assertThatThrownBy(
            () ->
                service.captureIdentity(
                    ORG,
                    "caja-participant",
                    new IdentityCaptureCommand(
                        "XEXX010101000", "WXYZ900101HDFRRN02", "Someone Else", Optional.empty())))
        .isInstanceOf(IdentityKeyDestroyedException.class);

    // key_destroyed_at is still the only evidence the erasure happened -- a refused capture must
    // not have cleared it as a side effect.
    assertThat(identities.find(ORG, "caja-participant").orElseThrow().keyDestroyedAt()).isPresent();
  }

  @Test
  @DisplayName("destroying one participant's key never affects another participant's identity")
  void destroyingOneKeyLeavesAnotherUnaffected() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));
    service.captureIdentity(
        ORG,
        "retirement-participant",
        new IdentityCaptureCommand(
            "XEXX010101000",
            "WXYZ900101HDFRRN02",
            "Maria Gonzalez",
            Optional.of(LocalDate.of(1985, 3, 2))));

    service.destroyKey(ORG, "caja-participant");

    assertThatThrownBy(() -> service.readIdentity(ORG, "caja-participant"))
        .isInstanceOf(IdentityKeyDestroyedException.class);
    DecryptedIdentity untouched = service.readIdentity(ORG, "retirement-participant");
    assertThat(untouched.rfc()).isEqualTo("XEXX010101000");
    assertThat(untouched.name()).isEqualTo("Maria Gonzalez");
  }

  @Test
  @DisplayName("destroying an already-destroyed key is a harmless no-op")
  void destroyingTwiceIsIdempotent() {
    service.captureIdentity(
        ORG,
        "caja-participant",
        new IdentityCaptureCommand(
            "XAXX010101000", "ABCD900101HDFRRN01", "Juan Perez", Optional.empty()));

    service.destroyKey(ORG, "caja-participant");
    service.destroyKey(ORG, "caja-participant");

    assertThatThrownBy(() -> service.readIdentity(ORG, "caja-participant"))
        .isInstanceOf(IdentityKeyDestroyedException.class);
  }

  @Test
  @DisplayName(
      "destroying a key for a participant with no captured identity is reported distinctly")
  void destroyingWithNoIdentityIsNotFound() {
    assertThatThrownBy(() -> service.destroyKey(ORG, "caja-participant"))
        .isInstanceOf(ParticipantIdentityNotFoundException.class);
  }

  private void enrol(String internalRef, String planId) {
    Instant now = CLOCK.instant();
    participants.save(
        new Participant(
            ORG,
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
  }

  private FundId registerFund(String vehicleType) {
    FundId fundId = FundId.of(UUID.randomUUID());
    funds.publish(
        new Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Fund " + vehicleType,
            vehicleType,
            CurrencyCode.MXN,
            1000L + Math.abs(vehicleType.hashCode() % 1000),
            Units.SCALE,
            LocalDate.of(2020, 1, 1),
            Nav.of(100_000_000L, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            CLOCK.instant()));
    return fundId;
  }

  private void registerPlan(String planId, FundId fundId) {
    planVersions.publish(
        new PlanVersion(
            ORG, planId, LocalDate.of(2020, 1, 1), 1, 1, 1, 0, 0, fundId, CLOCK.instant()));
  }

  /** A minimal in-memory {@link ParticipantRepository}: only {@link #find} is exercised here. */
  private static final class InMemoryParticipantRepository implements ParticipantRepository {
    private final Map<String, Participant> byRef = new HashMap<>();

    void save(Participant participant) {
      byRef.put(key(participant.organizationId(), participant.internalRef()), participant);
    }

    @Override
    public boolean insertIfAbsent(Participant participant) {
      String k = key(participant.organizationId(), participant.internalRef());
      if (byRef.containsKey(k)) {
        return false;
      }
      byRef.put(k, participant);
      return true;
    }

    @Override
    public Optional<Participant> find(OrganizationId organizationId, String internalRef) {
      return Optional.ofNullable(byRef.get(key(organizationId, internalRef)));
    }

    private static String key(OrganizationId organizationId, String internalRef) {
      return organizationId.value() + ":" + internalRef;
    }
  }

  /**
   * A minimal in-memory {@link ParticipantIdentityRepository} mirroring the JDBC upsert/destroy
   * semantics without a database.
   */
  private static final class InMemoryParticipantIdentityRepository
      implements ParticipantIdentityRepository {
    private final Map<String, ParticipantIdentity> byRef = new HashMap<>();

    @Override
    public void upsert(ParticipantIdentity identity) {
      byRef.put(key(identity.organizationId(), identity.internalRef()), identity);
    }

    @Override
    public Optional<ParticipantIdentity> find(OrganizationId organizationId, String internalRef) {
      return Optional.ofNullable(byRef.get(key(organizationId, internalRef)));
    }

    @Override
    public boolean destroyKey(
        OrganizationId organizationId, String internalRef, Instant destroyedAt) {
      ParticipantIdentity existing = byRef.get(key(organizationId, internalRef));
      if (existing == null || existing.wrappedDataKey().isEmpty()) {
        return false;
      }
      byRef.put(
          key(organizationId, internalRef),
          new ParticipantIdentity(
              existing.organizationId(),
              existing.internalRef(),
              Optional.empty(),
              existing.encryptedRfc(),
              existing.encryptedCurp(),
              existing.encryptedName(),
              existing.encryptedDateOfBirth(),
              existing.dateOfBirthRequired(),
              existing.identityCapturedAsOf(),
              existing.createdAt(),
              destroyedAt,
              Optional.of(destroyedAt)));
      return true;
    }

    private static String key(OrganizationId organizationId, String internalRef) {
      return organizationId.value() + ":" + internalRef;
    }
  }
}
