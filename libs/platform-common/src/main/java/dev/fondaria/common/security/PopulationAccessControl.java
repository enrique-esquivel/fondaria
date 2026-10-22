package dev.fondaria.common.security;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.Objects;

/**
 * Authorization policy enforcement for the three user populations (Participant, Employer Admin, and
 * Internal Staff), enforcing data segregation, delegated administration boundaries, and four-eyes
 * role separation.
 */
public final class PopulationAccessControl {

  private PopulationAccessControl() {}

  /**
   * Asserts that the caller is authorized to access the given participant's records.
   *
   * <p>Enforces:
   *
   * <ul>
   *   <li>A participant can access their own positions, movements, and statements only.
   *   <li>Access to another participant's data is strictly forbidden (HTTP 403).
   * </ul>
   *
   * @throws AccessDeniedProblemException if the caller is not authorized to access the
   *     participant's data
   */
  public static void assertParticipantAccess(CallerIdentity caller, ParticipantId requestedId) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(requestedId, "requestedId");

    if (caller.isParticipant()) {
      boolean matchesParticipantId =
          caller.participantId().map(id -> id.equals(requestedId)).orElse(false);
      boolean matchesSubject = caller.subject().equalsIgnoreCase(requestedId.toString());
      if (!matchesParticipantId && !matchesSubject) {
        throw new AccessDeniedProblemException(
            "Forbidden: Participant cannot access another participant's data");
      }
      return;
    }

    if (caller.isInternalStaff() && caller.hasStaffRole(StaffRole.SUPPORT)) {
      // Support staff has read access
      return;
    }

    if (caller.isInternalService()) {
      // Platform services communicating with service account identity
      return;
    }

    throw new AccessDeniedProblemException(
        "Forbidden: Caller is not authorized to access participant data");
  }

  /**
   * Asserts that the caller is authorized to access the given organization's records.
   *
   * <p>Enforces:
   *
   * <ul>
   *   <li>An employer admin's scope comes from their bound provisioning claim, never a request
   *       parameter.
   *   <li>An employer admin requesting another organization's data receives HTTP 403 Forbidden.
   *   <li>Changing the organization id in the request path is rejected.
   * </ul>
   *
   * @throws AccessDeniedProblemException if the caller is not authorized for the requested
   *     organization
   */
  public static void assertOrganizationAccess(
      CallerIdentity caller, OrganizationId requestedOrgId) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(requestedOrgId, "requestedOrgId");

    if (caller.isEmployerAdmin()) {
      if (caller.organizationId().isEmpty()
          || !caller.organizationId().get().equals(requestedOrgId)) {
        throw new AccessDeniedProblemException(
            "Forbidden: Employer admin cannot access another organization's data");
      }
      return;
    }

    if (caller.isInternalStaff()) {
      // Internal staff with operational or support roles can access organization operations
      return;
    }

    if (caller.isInternalService()) {
      return;
    }

    throw new AccessDeniedProblemException(
        "Forbidden: Caller is not authorized to access organization data");
  }

  /**
   * Asserts that the caller is authorized to create an administrator for the target organization.
   *
   * <p>Enforces:
   *
   * <ul>
   *   <li>Delegated administration: an employer admin can create further admins within their own
   *       organization only.
   *   <li>Creating an admin outside their own organization is rejected with HTTP 403 Forbidden.
   * </ul>
   *
   * @throws AccessDeniedProblemException if the caller attempts cross-organization admin creation
   */
  public static void assertDelegatedAdminCreation(
      CallerIdentity caller, OrganizationId targetOrgId) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(targetOrgId, "targetOrgId");

    if (caller.isEmployerAdmin()) {
      if (caller.organizationId().isEmpty() || !caller.organizationId().get().equals(targetOrgId)) {
        throw new AccessDeniedProblemException(
            "Forbidden: Employer admin cannot create an admin outside their organization");
      }
      return;
    }

    if (caller.isInternalStaff() && caller.hasStaffRole(StaffRole.OPERATIONS)) {
      return;
    }

    if (caller.isInternalService()) {
      return;
    }

    throw new AccessDeniedProblemException(
        "Forbidden: Caller is not authorized to create organization administrators");
  }

  /**
   * Asserts that the caller may crypto-shred a participant's identity data key (Task 9.5).
   *
   * <p>Deliberately stricter than {@link #assertOrganizationAccess}: an employer admin may capture
   * identity data for their own organization's participants, but destroying a key is a
   * right-to-erasure action with no way back, and is restricted the same way {@link
   * #assertCanRequestFourEyesAction} restricts requesting a four-eyes action — an employer admin is
   * never the caller here, regardless of organization scope, and {@link StaffRole#SUPPORT}'s
   * read-only staff cannot trigger it either; this is the single most irreversible action in this
   * class and gets at least the same bar as the least of the others.
   *
   * @throws AccessDeniedProblemException if the caller is not operations staff or a service
   *     identity
   */
  public static void assertCanDestroyIdentityKey(CallerIdentity caller) {
    Objects.requireNonNull(caller, "caller");

    if (caller.isInternalService()) {
      return;
    }
    if (caller.isInternalStaff() && caller.hasStaffRole(StaffRole.OPERATIONS)) {
      return;
    }

    throw new AccessDeniedProblemException(
        "Forbidden: Destroying an identity key requires internal staff or a service identity");
  }

  /**
   * Asserts that the caller is authorized to request/initiate an action requiring four-eyes
   * approval.
   *
   * <p>Enforces:
   *
   * <ul>
   *   <li>The approver role cannot also request the action it approves.
   *   <li>Only operations staff can request/initiate operational actions.
   * </ul>
   *
   * @throws AccessDeniedProblemException if the caller holds the approver role or lacks operations
   *     role
   */
  public static void assertCanRequestFourEyesAction(CallerIdentity caller) {
    Objects.requireNonNull(caller, "caller");

    if (caller.hasStaffRole(StaffRole.APPROVER)) {
      throw new AccessDeniedProblemException(
          "Forbidden: The approver role cannot also request the action it approves");
    }

    if (!caller.isInternalStaff() || !caller.hasStaffRole(StaffRole.OPERATIONS)) {
      throw new AccessDeniedProblemException(
          "Forbidden: Operations role required to request four-eyes action");
    }
  }

  /**
   * Asserts that the caller is authorized to approve a four-eyes action.
   *
   * <p>Enforces:
   *
   * <ul>
   *   <li>Only internal staff with the APPROVER role can approve actions.
   *   <li>The same person requesting and approving is strictly forbidden.
   * </ul>
   *
   * @throws AccessDeniedProblemException if the caller is the requester or lacks the approver role
   */
  public static void assertCanApproveFourEyesAction(
      CallerIdentity caller, String requestedBySubject) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(requestedBySubject, "requestedBySubject");

    if (!caller.isInternalStaff() || !caller.hasStaffRole(StaffRole.APPROVER)) {
      throw new AccessDeniedProblemException(
          "Forbidden: Approver role required to approve four-eyes action");
    }

    if (caller.subject().equalsIgnoreCase(requestedBySubject)) {
      throw new AccessDeniedProblemException(
          "Forbidden: The requester cannot also approve their own action");
    }
  }
}
