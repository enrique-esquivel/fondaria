package dev.fondaria.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorisationEnforcerTest {

  private static final ParticipantId PARTICIPANT_1 =
      ParticipantId.parse("11111111-1111-1111-1111-111111111111");
  private static final ParticipantId PARTICIPANT_2 =
      ParticipantId.parse("22222222-2222-2222-2222-222222222222");

  private static final OrganizationId ORG_1 = new OrganizationId(UUID.randomUUID());
  private static final OrganizationId ORG_2 = new OrganizationId(UUID.randomUUID());

  @Test
  @DisplayName("Exempt paths allow unauthenticated callers")
  void exemptPathsAllowed() {
    assertThatCode(() -> AuthorisationEnforcer.enforce(Optional.empty(), "/actuator/health", "GET"))
        .doesNotThrowAnyException();
    assertThatCode(() -> AuthorisationEnforcer.enforce(Optional.empty(), "/error", "GET"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> AuthorisationEnforcer.enforce(Optional.empty(), "/swagger-ui/index.html", "GET"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Unauthenticated caller on protected route throws AuthenticationRequiredException (401)")
  void unauthenticatedThrowsAuthenticationRequired() {
    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.empty(), "/v1/participants/" + PARTICIPANT_1 + "/balance", "GET"))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Participant can access own participant route")
  void participantCanAccessOwn() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_1);

    assertThatCode(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/participants/" + PARTICIPANT_1 + "/balance", "GET"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Anti-enumeration: Participant accessing non-existent or other participant is denied 403 identically")
  void participantAccessingOtherDeniedAntiEnumeration() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_1);

    // Regardless of whether PARTICIPANT_2 exists, enforcement strictly throws 403 Forbidden
    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/participants/" + PARTICIPANT_2 + "/balance", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Participant cannot access another participant's data");
  }

  @Test
  @DisplayName("Employer admin cannot access participant routes")
  void employerAdminCannotAccessParticipant() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/participants/" + PARTICIPANT_1 + "/balance", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Caller is not authorized to access participant data");
  }

  @Test
  @DisplayName("Internal service can access participant routes and organization routes")
  void internalServiceCanAccess() {
    CallerIdentity caller = CallerIdentity.internalService("allotment-job");

    assertThatCode(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/participants/" + PARTICIPANT_1 + "/balance", "GET"))
        .doesNotThrowAnyException();

    assertThatCode(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/" + ORG_1 + "/summary", "GET"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Employer admin can access own organization routes but not others")
  void employerAdminOrgScoping() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);

    assertThatCode(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/" + ORG_1 + "/summary", "GET"))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/" + ORG_2 + "/summary", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Employer admin cannot access another organization's data");
  }

  @Test
  @DisplayName("Employer admin cannot create admin outside own organization")
  void employerAdminDelegatedAdminScoping() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);

    assertThatCode(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/" + ORG_1 + "/admins", "POST"))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/" + ORG_2 + "/admins", "POST"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Employer admin cannot create an admin outside their organization");
  }

  @Test
  @DisplayName(
      "Task 9.5: destroying an identity key requires internal staff, even for an employer admin's"
          + " own organization")
  void identityKeyDestructionRequiresStaff() {
    String path = "/v1/organizations/" + ORG_1 + "/participants/" + PARTICIPANT_1 + "/identity";

    CallerIdentity ownOrgAdmin = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatThrownBy(
            () -> AuthorisationEnforcer.enforce(Optional.of(ownOrgAdmin), path, "DELETE"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Destroying an identity key requires internal staff");

    CallerIdentity opsStaff = CallerIdentity.internalStaff("ops-1", StaffRole.OPERATIONS);
    assertThatCode(() -> AuthorisationEnforcer.enforce(Optional.of(opsStaff), path, "DELETE"))
        .doesNotThrowAnyException();

    // Read-only support staff cannot -- destroying an identity key is more than support's role.
    CallerIdentity supportStaff = CallerIdentity.internalStaff("support-1", StaffRole.SUPPORT);
    assertThatThrownBy(
            () -> AuthorisationEnforcer.enforce(Optional.of(supportStaff), path, "DELETE"))
        .isInstanceOf(AccessDeniedProblemException.class);

    // The same employer admin may still capture (PUT) identity data for their own organization --
    // only the DELETE (crypto-shred) path is restricted beyond the general organization scoping.
    assertThatCode(() -> AuthorisationEnforcer.enforce(Optional.of(ownOrgAdmin), path, "PUT"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Task 9.5: a matrix parameter on the identity path cannot bypass the staff-only DELETE rule")
  void identityKeyDestructionCannotBeBypassedWithMatrixParameter() {
    String pathWithMatrixParam =
        "/v1/organizations/" + ORG_1 + "/participants/" + PARTICIPANT_1 + "/identity;x=1";
    CallerIdentity ownOrgAdmin = CallerIdentity.employerAdmin("admin-1", ORG_1);

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(ownOrgAdmin), pathWithMatrixParam, "DELETE"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Destroying an identity key requires internal staff");
  }

  @Test
  @DisplayName("Staff actions require internal staff")
  void staffActionRequiresStaff() {
    CallerIdentity participant = CallerIdentity.participant(PARTICIPANT_1);

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(participant), "/v1/staff/actions", "POST"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Internal staff role required");

    CallerIdentity staff = CallerIdentity.internalStaff("staff-1", StaffRole.OPERATIONS);

    assertThatCode(
            () -> AuthorisationEnforcer.enforce(Optional.of(staff), "/v1/staff/actions", "POST"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Malformed participant or organization UUID is strictly denied 403 with audit logging")
  void malformedIdentifiersAreDenied403() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_1);

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/participants/not-a-valid-uuid/balance", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Malformed participant identifier");

    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller), "/v1/organizations/not-a-valid-uuid/summary", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Malformed organization identifier");
  }

  @Test
  @DisplayName("Default Deny: Unrecognized routes are strictly denied 403")
  void unrecognizedRoutesAreDeniedDefaultDeny() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_1);

    assertThatThrownBy(
            () -> AuthorisationEnforcer.enforce(Optional.of(caller), "/v1/unknown/endpoint", "GET"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Unrecognized or protected resource");
  }

  @Test
  @DisplayName("Path traversal attempts are normalized and prevented from bypassing authorization")
  void pathTraversalNormalizedAndPrevented() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_1);

    // Caller attempts traversal to access organization summary
    assertThatThrownBy(
            () ->
                AuthorisationEnforcer.enforce(
                    Optional.of(caller),
                    "/v1/participants/"
                        + PARTICIPANT_1
                        + "/../../organizations/"
                        + ORG_1
                        + "/summary",
                    "GET"))
        .isInstanceOf(AccessDeniedProblemException.class);
  }
}
