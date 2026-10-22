package dev.fondaria.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PopulationAccessControlTest {

  private static final ParticipantId PARTICIPANT_A =
      ParticipantId.parse("11111111-1111-1111-1111-111111111111");
  private static final ParticipantId PARTICIPANT_B =
      ParticipantId.parse("22222222-2222-2222-2222-222222222222");

  private static final OrganizationId ORG_1 = new OrganizationId(UUID.randomUUID());
  private static final OrganizationId ORG_2 = new OrganizationId(UUID.randomUUID());

  @Test
  @DisplayName("Participant requesting own data succeeds")
  void participantRequestingOwnDataSucceeds() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_A);
    assertThatCode(() -> PopulationAccessControl.assertParticipantAccess(caller, PARTICIPANT_A))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Participant requesting another participant's data throws 403 AccessDenied")
  void participantRequestingAnotherParticipantDataThrowsForbidden() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_A);
    assertThatThrownBy(() -> PopulationAccessControl.assertParticipantAccess(caller, PARTICIPANT_B))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Participant cannot access another participant's data");
  }

  @Test
  @DisplayName("Employer admin cannot access participant data")
  void employerAdminCannotAccessParticipantData() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatThrownBy(() -> PopulationAccessControl.assertParticipantAccess(caller, PARTICIPANT_A))
        .isInstanceOf(AccessDeniedProblemException.class);
  }

  @Test
  @DisplayName("Internal support staff can access participant data")
  void internalSupportCanAccessParticipantData() {
    CallerIdentity caller = CallerIdentity.internalStaff("support-1", StaffRole.SUPPORT);
    assertThatCode(() -> PopulationAccessControl.assertParticipantAccess(caller, PARTICIPANT_A))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Employer admin requesting own organization data succeeds")
  void employerAdminRequestingOwnOrganizationDataSucceeds() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatCode(() -> PopulationAccessControl.assertOrganizationAccess(caller, ORG_1))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Employer admin requesting another organization data throws 403 AccessDenied")
  void employerAdminRequestingAnotherOrganizationDataThrowsForbidden() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatThrownBy(() -> PopulationAccessControl.assertOrganizationAccess(caller, ORG_2))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Employer admin cannot access another organization's data");
  }

  @Test
  @DisplayName("Participant requesting organization data throws 403 AccessDenied")
  void participantRequestingOrganizationDataThrowsForbidden() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_A);
    assertThatThrownBy(() -> PopulationAccessControl.assertOrganizationAccess(caller, ORG_1))
        .isInstanceOf(AccessDeniedProblemException.class);
  }

  @Test
  @DisplayName("Employer admin can create admin within own organization")
  void employerAdminCanCreateAdminInOwnOrganization() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatCode(() -> PopulationAccessControl.assertDelegatedAdminCreation(caller, ORG_1))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Employer admin cannot create admin outside their organization")
  void employerAdminCannotCreateAdminOutsideOwnOrganization() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatThrownBy(() -> PopulationAccessControl.assertDelegatedAdminCreation(caller, ORG_2))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Employer admin cannot create an admin outside their organization");
  }

  @Test
  @DisplayName("Operations role can request four-eyes action")
  void operationsRoleCanRequestFourEyesAction() {
    CallerIdentity caller = CallerIdentity.internalStaff("op-user-1", StaffRole.OPERATIONS);
    assertThatCode(() -> PopulationAccessControl.assertCanRequestFourEyesAction(caller))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("The approver role cannot also request the action it approves")
  void approverRoleCannotRequestActionItApproves() {
    // User with APPROVER role (even if also granted operations) cannot request
    CallerIdentity caller =
        CallerIdentity.internalStaff("approver-1", StaffRole.APPROVER, StaffRole.OPERATIONS);
    assertThatThrownBy(() -> PopulationAccessControl.assertCanRequestFourEyesAction(caller))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("The approver role cannot also request the action it approves");
  }

  @Test
  @DisplayName("Support role cannot request four-eyes action")
  void supportRoleCannotRequestFourEyesAction() {
    CallerIdentity caller = CallerIdentity.internalStaff("support-1", StaffRole.SUPPORT);
    assertThatThrownBy(() -> PopulationAccessControl.assertCanRequestFourEyesAction(caller))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Operations role required");
  }

  @Test
  @DisplayName("Approver can approve action requested by different operator")
  void approverCanApproveActionRequestedByDifferentOperator() {
    CallerIdentity approver = CallerIdentity.internalStaff("approver-1", StaffRole.APPROVER);
    assertThatCode(
            () -> PopulationAccessControl.assertCanApproveFourEyesAction(approver, "op-user-1"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Same person requesting and approving is rejected")
  void samePersonRequestingAndApprovingIsRejected() {
    CallerIdentity approver = CallerIdentity.internalStaff("op-user-1", StaffRole.APPROVER);
    assertThatThrownBy(
            () -> PopulationAccessControl.assertCanApproveFourEyesAction(approver, "op-user-1"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("The requester cannot also approve their own action");
  }

  @Test
  @DisplayName("Non-approver cannot approve four-eyes action")
  void nonApproverCannotApproveFourEyesAction() {
    CallerIdentity op = CallerIdentity.internalStaff("op-user-2", StaffRole.OPERATIONS);
    assertThatThrownBy(
            () -> PopulationAccessControl.assertCanApproveFourEyesAction(op, "op-user-1"))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining("Approver role required");
  }

  @Test
  @DisplayName("Task 9.5: internal operations staff can destroy an identity key")
  void internalOperationsStaffCanDestroyIdentityKey() {
    CallerIdentity caller = CallerIdentity.internalStaff("ops-1", StaffRole.OPERATIONS);
    assertThatCode(() -> PopulationAccessControl.assertCanDestroyIdentityKey(caller))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Task 9.5: read-only support staff cannot destroy an identity key")
  void readOnlySupportStaffCannotDestroyIdentityKey() {
    CallerIdentity caller = CallerIdentity.internalStaff("support-1", StaffRole.SUPPORT);
    assertThatThrownBy(() -> PopulationAccessControl.assertCanDestroyIdentityKey(caller))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining(
            "Destroying an identity key requires internal staff or a service" + " identity");
  }

  @Test
  @DisplayName("Task 9.5: an internal service identity can destroy an identity key")
  void internalServiceCanDestroyIdentityKey() {
    CallerIdentity caller = CallerIdentity.internalService("contribution-worker");
    assertThatCode(() -> PopulationAccessControl.assertCanDestroyIdentityKey(caller))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Task 9.5: an employer admin cannot destroy an identity key, even for their own org")
  void employerAdminCannotDestroyIdentityKey() {
    CallerIdentity caller = CallerIdentity.employerAdmin("admin-1", ORG_1);
    assertThatThrownBy(() -> PopulationAccessControl.assertCanDestroyIdentityKey(caller))
        .isInstanceOf(AccessDeniedProblemException.class)
        .hasMessageContaining(
            "Destroying an identity key requires internal staff or a service" + " identity");
  }

  @Test
  @DisplayName("Task 9.5: a participant cannot destroy an identity key")
  void participantCannotDestroyIdentityKey() {
    CallerIdentity caller = CallerIdentity.participant(PARTICIPANT_A);
    assertThatThrownBy(() -> PopulationAccessControl.assertCanDestroyIdentityKey(caller))
        .isInstanceOf(AccessDeniedProblemException.class);
  }
}
