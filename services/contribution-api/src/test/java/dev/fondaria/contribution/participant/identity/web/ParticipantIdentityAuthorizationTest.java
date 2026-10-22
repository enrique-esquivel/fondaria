package dev.fondaria.contribution.participant.identity.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.contribution.fund.InMemoryFundRepository;
import dev.fondaria.contribution.participant.EmploymentStatus;
import dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository;
import dev.fondaria.contribution.participant.Participant;
import dev.fondaria.contribution.participant.ParticipantLookup;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.contribution.participant.ParticipantStatus;
import dev.fondaria.contribution.participant.identity.ParticipantIdentity;
import dev.fondaria.contribution.participant.identity.ParticipantIdentityMasterKey;
import dev.fondaria.contribution.participant.identity.ParticipantIdentityRepository;
import dev.fondaria.contribution.participant.identity.ParticipantIdentityService;
import dev.fondaria.contribution.plan.InMemoryPlanVersionRepository;
import dev.fondaria.contribution.security.IdentityContextFilter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Task 9.5's authorization boundary: an employer admin may capture identity data for their own
 * organization's participants only, and destroying a key (crypto-shredding) is internal staff / a
 * service identity only — never an employer admin, even for their own organization. Enforced
 * entirely by {@code AuthorisationEnforcer} (Task 9.3), exercised here the same way {@code
 * ContributionApiAuthorizationTest} exercises the rest of it.
 */
class ParticipantIdentityAuthorizationTest {

  private static final OrganizationId ORG_1 =
      new OrganizationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final OrganizationId ORG_2 =
      new OrganizationId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
  private static final ParticipantId PARTICIPANT_ID = ParticipantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    InMemoryParticipantLookupRepository lookups = new InMemoryParticipantLookupRepository();
    lookups.add(ORG_1, new ParticipantLookup(PARTICIPANT_ID, "P-001", "PAYROLL-001"));

    ParticipantRepository participants = new StubParticipantRepository();
    ((StubParticipantRepository) participants)
        .save(
            new Participant(
                ORG_1,
                "P-001",
                "PLAN-CAJA",
                "PAYROLL-001",
                ParticipantStatus.ACTIVE,
                EmploymentStatus.ACTIVE,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 1),
                Optional.empty(),
                CLOCK.instant(),
                CLOCK.instant()));

    byte[] rawKey = new byte[32];
    new SecureRandom().nextBytes(rawKey);
    ParticipantIdentityMasterKey masterKey = new ParticipantIdentityMasterKey(rawKey);

    InMemoryFundRepository funds = new InMemoryFundRepository();
    dev.fondaria.common.FundId fundId = dev.fondaria.common.FundId.of(UUID.randomUUID());
    funds.publish(
        new dev.fondaria.rules.Fund(
            fundId,
            LocalDate.of(2020, 1, 1),
            1,
            "Caja de Ahorro Uno",
            "CAJA_DE_AHORRO",
            dev.fondaria.common.CurrencyCode.MXN,
            1_000L,
            dev.fondaria.common.Units.SCALE,
            LocalDate.of(2020, 1, 1),
            dev.fondaria.common.Nav.of(100_000_000L, dev.fondaria.common.CurrencyCode.MXN),
            dev.fondaria.rules.FundStatus.ACTIVE,
            CLOCK.instant()));
    InMemoryPlanVersionRepository planVersions = new InMemoryPlanVersionRepository();
    planVersions.publish(
        new dev.fondaria.rules.PlanVersion(
            ORG_1, "PLAN-CAJA", LocalDate.of(2020, 1, 1), 1, 1, 1, 0, 0, fundId, CLOCK.instant()));

    ParticipantIdentityService service =
        new ParticipantIdentityService(
            participants,
            new StubParticipantIdentityRepository(),
            planVersions,
            funds,
            masterKey,
            CLOCK);

    ParticipantIdentityController controller = new ParticipantIdentityController(lookups, service);

    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ParticipantIdentityExceptionHandler())
            .addFilter(new IdentityContextFilter(mapper))
            .build();
  }

  private String path() {
    return "/v1/organizations/" + ORG_1 + "/participants/" + PARTICIPANT_ID + "/identity";
  }

  private String captureBody() {
    return """
        {"rfc":"XAXX010101000","curp":"ABCD900101HDFRRN01","name":"Juan Perez"}
        """;
  }

  private String createToken(String sub, List<String> roles, Map<String, Object> extraClaims)
      throws Exception {
    String header =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes());
    var payloadMap = new HashMap<String, Object>();
    payloadMap.put("sub", sub);
    payloadMap.put("realm_access", Map.of("roles", roles));
    payloadMap.putAll(extraClaims);
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(payloadMap));
    return header + "." + payload + ".dummy-signature";
  }

  @Test
  @DisplayName("Unauthenticated capture request -> 401")
  void unauthenticatedCaptureReturns401() throws Exception {
    mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content(captureBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  @DisplayName("Employer admin can capture identity for their own organization's participant")
  void employerAdminCanCaptureOwnOrgIdentity() throws Exception {
    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));

    mvc.perform(
            put(path())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureBody()))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName(
      "Employer admin cannot capture identity for another organization's participant -> 403")
  void employerAdminCannotCaptureAnotherOrgIdentity() throws Exception {
    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_2.toString()));

    mvc.perform(
            put(path())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("A participant cannot capture anyone's identity -> 403")
  void participantCannotCaptureIdentity() throws Exception {
    String token = createToken("participant-1", List.of("participant"), Map.of());

    mvc.perform(
            put(path())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Internal staff can destroy an identity key (crypto-shred)")
  void internalOperationsStaffCanDestroyKey() throws Exception {
    String captureToken =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));
    mvc.perform(
        put(path())
            .header("Authorization", "Bearer " + captureToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(captureBody()));

    String staffToken =
        createToken("staff-1", List.of("internal-staff", "internal-operations"), Map.of());
    mvc.perform(delete(path()).header("Authorization", "Bearer " + staffToken))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Read-only support staff cannot destroy an identity key -> 403")
  void readOnlySupportStaffCannotDestroyKey() throws Exception {
    String captureToken =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));
    mvc.perform(
        put(path())
            .header("Authorization", "Bearer " + captureToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(captureBody()));

    String supportToken =
        createToken("support-1", List.of("internal-staff", "internal-support"), Map.of());
    mvc.perform(delete(path()).header("Authorization", "Bearer " + supportToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName(
      "An employer admin cannot destroy an identity key, even for their own organization -> 403")
  void employerAdminCannotDestroyKeyEvenForOwnOrg() throws Exception {
    String captureToken =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));
    mvc.perform(
        put(path())
            .header("Authorization", "Bearer " + captureToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(captureBody()));

    mvc.perform(delete(path()).header("Authorization", "Bearer " + captureToken))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Forbidden: Destroying an identity key requires internal staff or a service"
                        + " identity"));
  }

  @Test
  @DisplayName("An internal service identity can both capture and destroy")
  void internalServiceCanCaptureAndDestroy() throws Exception {
    String token = createToken("contribution-worker", List.of("internal-service"), Map.of());

    mvc.perform(
            put(path())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureBody()))
        .andExpect(status().isNoContent());

    mvc.perform(delete(path()).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  /** A minimal in-memory {@link ParticipantRepository}, local to this authorization test. */
  private static final class StubParticipantRepository implements ParticipantRepository {
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
   * A minimal in-memory {@link ParticipantIdentityRepository}, local to this authorization test.
   */
  private static final class StubParticipantIdentityRepository
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
