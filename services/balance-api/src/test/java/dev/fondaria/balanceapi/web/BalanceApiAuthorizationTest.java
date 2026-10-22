package dev.fondaria.balanceapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.balanceapi.asof.AsOfBalance;
import dev.fondaria.balanceapi.asof.AsOfBalanceService;
import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.balance.ParticipantBalance;
import dev.fondaria.balanceapi.movement.MovementPage;
import dev.fondaria.balanceapi.movement.MovementService;
import dev.fondaria.balanceapi.security.IdentityContextFilter;
import dev.fondaria.balanceapi.summary.OrganizationSummary;
import dev.fondaria.balanceapi.summary.OrganizationSummaryService;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BalanceApiAuthorizationTest {

  private static final ParticipantId PARTICIPANT_A =
      ParticipantId.parse("11111111-1111-1111-1111-111111111111");
  private static final ParticipantId PARTICIPANT_B =
      ParticipantId.parse("22222222-2222-2222-2222-222222222222");
  private static final ParticipantId NON_EXISTENT_PARTICIPANT =
      ParticipantId.parse("99999999-9999-9999-9999-999999999999");

  private static final OrganizationId ORG_1 = new OrganizationId(UUID.randomUUID());
  private static final OrganizationId ORG_2 = new OrganizationId(UUID.randomUUID());

  private BalanceService balanceService;
  private MovementService movementService;
  private AsOfBalanceService asOfBalanceService;
  private OrganizationSummaryService summaryService;
  private MockMvc mvc;
  private final ObjectMapper mapper = new ObjectMapper();

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    auditLogger = (Logger) LoggerFactory.getLogger("security.audit");
    appender = new ListAppender<>();
    appender.start();
    auditLogger.addAppender(appender);
    auditLogger.setLevel(Level.INFO);

    balanceService = Mockito.mock(BalanceService.class);
    movementService = Mockito.mock(MovementService.class);
    asOfBalanceService = Mockito.mock(AsOfBalanceService.class);
    summaryService = Mockito.mock(OrganizationSummaryService.class);

    when(balanceService.balanceOf(PARTICIPANT_A))
        .thenReturn(
            new ParticipantBalance(
                PARTICIPANT_A,
                Instant.now(),
                Money.of(100, dev.fondaria.common.CurrencyCode.MXN),
                List.of(),
                false));
    when(movementService.movements(any())).thenReturn(MovementPage.last(List.of()));
    when(asOfBalanceService.balanceAsOf(any(), any()))
        .thenReturn(
            new AsOfBalance(
                PARTICIPANT_A,
                LocalDate.now(),
                Money.of(100, dev.fondaria.common.CurrencyCode.MXN),
                List.of()));
    when(summaryService.summaryOf(ORG_1))
        .thenReturn(
            new OrganizationSummary(
                ORG_1,
                Instant.now(),
                10,
                Money.of(100, dev.fondaria.common.CurrencyCode.MXN),
                Money.of(100, dev.fondaria.common.CurrencyCode.MXN),
                new dev.fondaria.balanceapi.summary.StatementPeriod(
                    LocalDate.now().minusDays(30), LocalDate.now()),
                List.of(),
                false));

    BalanceController balanceController = new BalanceController(balanceService);
    MovementController movementController = new MovementController(movementService);
    BalanceAsOfController asOfController = new BalanceAsOfController(asOfBalanceService);
    OrganizationSummaryController summaryController =
        new OrganizationSummaryController(summaryService);

    mvc =
        MockMvcBuilders.standaloneSetup(
                balanceController, movementController, asOfController, summaryController)
            .setControllerAdvice(
                new BalanceExceptionHandler(),
                new MovementExceptionHandler(),
                new BalanceAsOfExceptionHandler(),
                new OrganizationSummaryExceptionHandler())
            .addFilter(new IdentityContextFilter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    auditLogger.detachAppender(appender);
  }

  private String participantToken(ParticipantId id) {
    return """
        {"sub":"%s","realm_access":{"roles":["participant"]}}
        """
        .formatted(id);
  }

  private String employerAdminToken(OrganizationId orgId) {
    return """
        {"sub":"admin-1","organization_id":"%s","realm_access":{"roles":["employer-admin"]}}
        """
        .formatted(orgId);
  }

  private String internalStaffToken(String role) {
    return """
        {"sub":"staff-1","realm_access":{"roles":["internal-staff","%s"]}}
        """
        .formatted(role);
  }

  private String internalServiceToken(String serviceName) {
    return """
        {"sub":"%s","realm_access":{"roles":["internal-service"]}}
        """
        .formatted(serviceName);
  }

  @Test
  @DisplayName("Unauthenticated caller receives 401 Unauthorized ProblemDetail on all endpoints")
  void unauthenticatedCallerReturns401() throws Exception {
    mvc.perform(get("/v1/participants/{id}/balance", PARTICIPANT_A))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    mvc.perform(get("/v1/participants/{id}/positions", PARTICIPANT_A))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    mvc.perform(get("/v1/participants/{id}/movements", PARTICIPANT_A))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    mvc.perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT_A, "2026-01-15"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    mvc.perform(get("/v1/organizations/{id}/summary", ORG_1))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  @DisplayName(
      "Anti-enumeration: Requesting existing vs non-existent participant yields identical 403 Forbidden")
  void antiEnumerationYieldsIdenticalForbiddenResponses() throws Exception {
    String token = participantToken(PARTICIPANT_A);

    // Existing participant B
    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_B)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("Forbidden: Participant cannot access another participant's data"));

    // Non-existent participant
    mvc.perform(
            get("/v1/participants/{id}/balance", NON_EXISTENT_PARTICIPANT)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("Forbidden: Participant cannot access another participant's data"));
  }

  @Test
  @DisplayName("Internal service caller can access participant and organization endpoints")
  void internalServiceCanAccessEndpoints() throws Exception {
    String token = internalServiceToken("allotment-job");

    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_A)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    mvc.perform(
            get("/v1/organizations/{id}/summary", ORG_1).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Internal staff caller can access organization endpoints")
  void internalStaffCanAccessEndpoints() throws Exception {
    String token = internalStaffToken("internal-operations");

    mvc.perform(
            get("/v1/organizations/{id}/summary", ORG_1).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Participant requesting own balance succeeds 200 and logs decision exactly once")
  void participantRequestingOwnBalanceSucceeds() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_A)
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isOk());

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("AUDIT_DECISION")
        .contains("outcome=PERMITTED");
  }

  @Test
  @DisplayName("Participant requesting another participant's balance returns 403 Forbidden")
  void participantRequestingAnotherParticipantBalanceReturns403() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_B)
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Participant requesting another participant's positions returns 403 Forbidden")
  void participantRequestingAnotherParticipantPositionsReturns403() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/positions", PARTICIPANT_B)
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Participant requesting another participant's movements returns 403 Forbidden")
  void participantRequestingAnotherParticipantMovementsReturns403() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/movements", PARTICIPANT_B)
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Participant requesting another participant's balance-as-of returns 403 Forbidden")
  void participantRequestingAnotherParticipantBalanceAsOfReturns403() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT_B, "2026-01-15")
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Employer admin requesting participant data returns 403 Forbidden")
  void employerAdminRequestingParticipantDataReturns403() throws Exception {
    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_A)
                .header("Authorization", "Bearer " + employerAdminToken(ORG_1)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Employer admin requesting own organization summary succeeds 200")
  void employerAdminRequestingOwnOrganizationSummarySucceeds() throws Exception {
    mvc.perform(
            get("/v1/organizations/{id}/summary", ORG_1)
                .header("Authorization", "Bearer " + employerAdminToken(ORG_1)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Employer admin requesting another organization summary returns 403 Forbidden")
  void employerAdminRequestingAnotherOrganizationSummaryReturns403() throws Exception {
    mvc.perform(
            get("/v1/organizations/{id}/summary", ORG_2)
                .header("Authorization", "Bearer " + employerAdminToken(ORG_1)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Participant requesting organization summary returns 403 Forbidden")
  void participantRequestingOrganizationSummaryReturns403() throws Exception {
    mvc.perform(
            get("/v1/organizations/{id}/summary", ORG_1)
                .header("Authorization", "Bearer " + participantToken(PARTICIPANT_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"));
  }

  @Test
  @DisplayName("Internal staff without support role requesting participant balance returns 403")
  void internalStaffOperationsRequestingParticipantBalanceReturns403() throws Exception {
    String opsToken = internalStaffToken("internal-operations");
    mvc.perform(
            get("/v1/participants/{id}/balance", PARTICIPANT_A)
                .header("Authorization", "Bearer " + opsToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Internal staff with support role can access participant positions and movements")
  void internalSupportStaffCanAccessParticipantEndpoints() throws Exception {
    String supportToken = internalStaffToken("internal-support");
    mvc.perform(
            get("/v1/participants/{id}/positions", PARTICIPANT_A)
                .header("Authorization", "Bearer " + supportToken))
        .andExpect(status().isOk());

    mvc.perform(
            get("/v1/participants/{id}/movements", PARTICIPANT_A)
                .header("Authorization", "Bearer " + supportToken))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Internal service can access participant positions and movements")
  void internalServiceCanAccessPositionsAndMovements() throws Exception {
    String serviceToken = internalServiceToken("statement-worker");
    mvc.perform(
            get("/v1/participants/{id}/positions", PARTICIPANT_A)
                .header("Authorization", "Bearer " + serviceToken))
        .andExpect(status().isOk());

    mvc.perform(
            get("/v1/participants/{id}/movements", PARTICIPANT_A)
                .header("Authorization", "Bearer " + serviceToken))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Path traversal attempt in participant route is normalized and evaluated strictly")
  void pathTraversalAttemptRejected() throws Exception {
    String token = participantToken(PARTICIPANT_A);
    // Attempting /v1/participants/PARTICIPANT_A/../../organizations/ORG_1/summary
    mvc.perform(
            get("/v1/participants/" + PARTICIPANT_A + "/../../organizations/" + ORG_1 + "/summary")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
