package dev.fondaria.contribution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.admin.web.AdminController;
import dev.fondaria.contribution.admin.web.AdminExceptionHandler;
import dev.fondaria.contribution.security.IdentityContextFilter;
import dev.fondaria.contribution.staff.web.StaffActionController;
import dev.fondaria.contribution.staff.web.StaffActionExceptionHandler;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Task 9.3: Contribution API authorization and populations")
class ContributionApiAuthorizationTest {

  private static final OrganizationId ORG_1 =
      new OrganizationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final OrganizationId ORG_2 =
      new OrganizationId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mvc;

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level originalAuditLevel;

  @BeforeEach
  void setUp() {
    auditLogger = (Logger) LoggerFactory.getLogger("security.audit");
    appender = new ListAppender<>();
    appender.start();
    auditLogger.addAppender(appender);
    originalAuditLevel = auditLogger.getLevel();
    auditLogger.setLevel(Level.INFO);

    AdminController adminController = new AdminController();
    dev.fondaria.contribution.staff.StaffActionRepository staffRepo =
        new dev.fondaria.contribution.staff.InMemoryStaffActionRepository();
    io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    java.time.Clock clock = java.time.Clock.systemUTC();
    dev.fondaria.contribution.staff.BreakGlassMetrics breakGlassMetrics =
        new dev.fondaria.contribution.staff.BreakGlassMetrics(meterRegistry, staffRepo, clock);
    StaffActionController staffActionController =
        new StaffActionController(staffRepo, clock, mapper, breakGlassMetrics);

    mvc =
        MockMvcBuilders.standaloneSetup(adminController, staffActionController)
            .setControllerAdvice(new AdminExceptionHandler(), new StaffActionExceptionHandler())
            .addFilter(new IdentityContextFilter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    auditLogger.detachAppender(appender);
    auditLogger.setLevel(originalAuditLevel);
  }

  private String createToken(String sub, List<String> roles, Map<String, Object> extraClaims)
      throws Exception {
    String header =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes());
    var payloadMap = new java.util.HashMap<String, Object>();
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
  @DisplayName("Unauthenticated request returns 401 Unauthorized ProblemDetail")
  void unauthenticatedReturns401() throws Exception {
    mvc.perform(
            post("/v1/organizations/" + ORG_1 + "/admins")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"new.admin","email":"new@example.com"}
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    mvc.perform(
            post("/v1/staff/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"actionType":"KILL_SWITCH","description":"Halt processing"}
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  @DisplayName("Employer admin can create admin within own organization")
  void employerAdminCanCreateAdminWithinOwnOrg() throws Exception {
    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));

    mvc.perform(
            post("/v1/organizations/" + ORG_1 + "/admins")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"new.admin","email":"new@example.com"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.organizationId").value(ORG_1.toString()))
        .andExpect(jsonPath("$.username").value("new.admin"));

    assertThat(appender.list).isNotEmpty();
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("AUDIT_DECISION")
        .contains("outcome=PERMITTED");
  }

  @Test
  @DisplayName("Employer admin cannot create admin outside their organization -> 403")
  void employerAdminCannotCreateAdminOutsideOwnOrg() throws Exception {
    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));

    mvc.perform(
            post("/v1/organizations/" + ORG_2 + "/admins")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"new.admin","email":"new@example.com"}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Forbidden: Employer admin cannot create an admin outside their organization"));
  }

  @Test
  @DisplayName("Participant cannot create admin -> 403")
  void participantCannotCreateAdmin() throws Exception {
    String token = createToken("participant-1", List.of("participant"), Map.of());

    mvc.perform(
            post("/v1/organizations/" + ORG_1 + "/admins")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"new.admin","email":"new@example.com"}
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Internal service can access organization endpoints")
  void internalServiceCanAccess() throws Exception {
    String token = createToken("allotment-job", List.of("internal-service"), Map.of());

    mvc.perform(
            post("/v1/organizations/" + ORG_1 + "/admins")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"new.admin","email":"new@example.com"}
                    """))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("Internal operations staff can request four-eyes action")
  void internalOperationsCanRequestAction() throws Exception {
    String token =
        createToken("ops-user-1", List.of("internal-staff", "internal-operations"), Map.of());

    mvc.perform(
            post("/v1/staff/actions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"actionType":"KILL_SWITCH","description":"Halt processing for plan"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.requestedBy").value("ops-user-1"));
  }

  @Test
  @DisplayName("Approver role cannot request four-eyes action -> 403")
  void approverCannotRequestAction() throws Exception {
    String token =
        createToken("approver-user-1", List.of("internal-staff", "internal-approver"), Map.of());

    mvc.perform(
            post("/v1/staff/actions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"actionType":"KILL_SWITCH","description":"Halt processing for plan"}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("Forbidden: The approver role cannot also request the action it approves"));
  }

  @Test
  @DisplayName("Four-eyes approval: distinct approver succeeds")
  void distinctApproverSucceeds() throws Exception {
    String opsToken =
        createToken("ops-user-1", List.of("internal-staff", "internal-operations"), Map.of());

    MvcResult result =
        mvc.perform(
                post("/v1/staff/actions")
                    .header("Authorization", "Bearer " + opsToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"actionType":"KILL_SWITCH","description":"Halt processing for plan"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();

    Map<?, ?> action = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    String actionId = (String) action.get("actionId");

    String approverToken =
        createToken("approver-user-2", List.of("internal-staff", "internal-approver"), Map.of());

    mvc.perform(
            post("/v1/staff/actions/" + actionId + "/approve")
                .header("Authorization", "Bearer " + approverToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.approvedBy").value("approver-user-2"));
  }

  @Test
  @DisplayName("Four-eyes approval: same person requesting and approving -> 403")
  void samePersonRequestingAndApprovingFails() throws Exception {
    String dualRoleToken =
        createToken("superuser-1", List.of("internal-staff", "internal-operations"), Map.of());

    MvcResult result =
        mvc.perform(
                post("/v1/staff/actions")
                    .header("Authorization", "Bearer " + dualRoleToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"actionType":"CORRECTION","description":"Override NAV"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();

    Map<?, ?> action = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    String actionId = (String) action.get("actionId");

    String approverTokenSameUser =
        createToken("superuser-1", List.of("internal-staff", "internal-approver"), Map.of());

    mvc.perform(
            post("/v1/staff/actions/" + actionId + "/approve")
                .header("Authorization", "Bearer " + approverTokenSameUser))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("Forbidden: The requester cannot also approve their own action"));
  }

  @Test
  @DisplayName("Employer admin requesting another organization's participant lookup -> 403")
  void employerAdminCrossOrgParticipantLookupFails() throws Exception {
    dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository repo =
        new dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository();
    dev.fondaria.contribution.participant.web.ParticipantLookupController controller =
        new dev.fondaria.contribution.participant.web.ParticipantLookupController(repo);

    MockMvc participantMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new dev.fondaria.contribution.participant.web.ParticipantLookupExceptionHandler())
            .addFilter(new IdentityContextFilter(mapper))
            .build();

    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));

    participantMvc
        .perform(
            get("/v1/organizations/" + ORG_2 + "/participants?internalRef=P-001")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("Forbidden: Employer admin cannot access another organization's data"));
  }

  @Test
  @DisplayName("Changing organization id in path has no effect / returns 403")
  void changingOrgIdInPathReturnsForbidden() throws Exception {
    dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository repo =
        new dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository();
    dev.fondaria.contribution.participant.web.ParticipantLookupController controller =
        new dev.fondaria.contribution.participant.web.ParticipantLookupController(repo);

    MockMvc participantMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new dev.fondaria.contribution.participant.web.ParticipantLookupExceptionHandler())
            .addFilter(new IdentityContextFilter(mapper))
            .build();

    String token =
        createToken("admin-1", List.of("employer-admin"), Map.of("org_id", ORG_1.toString()));

    // Admin belongs to ORG_1, but tries to change path to ORG_2
    participantMvc
        .perform(
            get("/v1/organizations/" + ORG_2 + "/participants?internalRef=P-001")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
