package dev.fondaria.common.security;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single enforcement point for service authorization decisions (Task 9.3).
 *
 * <p>Requirements:
 *
 * <ul>
 *   <li>Single enforcement point per service; no scattered role checks in controllers.
 *   <li>Every decision produces an audit entry: subject, resource, action, outcome, reason.
 *   <li>Denial responses distinguish:
 *       <ul>
 *         <li><i>not authenticated</i> -> 401 (AuthenticationRequiredException)
 *         <li><i>not authorised</i> -> 403 (AccessDeniedProblemException)
 *         <li><i>not found</i> -> 404 (handled by service lookup if authorised)
 *       </ul>
 *   <li>Anti-enumeration: Evaluating participant access happens *before* resource lookup.
 *       Unauthorised callers receive 403 identically regardless of whether the target participant
 *       exists.
 *   <li>Default Deny: Any unmatched route or malformed path parameter on protected resources
 *       results in denial.
 * </ul>
 */
public final class AuthorisationEnforcer {

  private static final Pattern PARTICIPANT_PATH_PATTERN =
      Pattern.compile("^/v1/participants/([^/]+)(?:/.*)?$");
  private static final Pattern ORGANIZATION_PATH_PATTERN =
      Pattern.compile("^/v1/organizations/([^/]+)(?:/.*)?$");

  private AuthorisationEnforcer() {}

  /**
   * Evaluates authorization for an incoming HTTP request.
   *
   * @param callerOptional the authenticated caller identity, if present
   * @param rawPath the request URI path
   * @param method the HTTP method (e.g. GET, POST)
   * @throws AuthenticationRequiredException if unauthenticated
   * @throws AccessDeniedProblemException if unauthorized
   */
  public static void enforce(
      Optional<CallerIdentity> callerOptional, String rawPath, String method) {
    Objects.requireNonNull(rawPath, "rawPath");
    Objects.requireNonNull(method, "method");

    // Path normalization to avoid directory traversal bypasses
    String path = normalizePath(rawPath);

    // Public / health / metrics endpoints are exempt from authorization
    if (isExemptPath(path)) {
      return;
    }

    if (callerOptional == null || callerOptional.isEmpty()) {
      SecurityDecision decision =
          SecurityDecision.denied(
              "anonymous", path, method, "Missing or unverified authentication token");
      SecurityAuditLogger.log(decision);
      throw new AuthenticationRequiredException();
    }

    CallerIdentity caller = callerOptional.get();

    // 1. Participant endpoints check (/v1/participants/{participantId}...)
    Matcher participantMatcher = PARTICIPANT_PATH_PATTERN.matcher(path);
    if (participantMatcher.matches()) {
      String pIdRaw = participantMatcher.group(1);
      ParticipantId targetId;
      try {
        targetId = ParticipantId.parse(pIdRaw);
      } catch (IllegalArgumentException e) {
        AccessDeniedProblemException ex =
            new AccessDeniedProblemException(
                "Forbidden: Malformed participant identifier in request path");
        SecurityAuditLogger.log(
            SecurityDecision.denied(caller.subject(), path, method, ex.detail()));
        throw ex;
      }

      try {
        PopulationAccessControl.assertParticipantAccess(caller, targetId);
        SecurityDecision decision =
            SecurityDecision.permitted(
                caller.subject(), path, method, "Participant access permitted");
        SecurityAuditLogger.log(decision);
        return;
      } catch (AccessDeniedProblemException e) {
        SecurityDecision decision =
            SecurityDecision.denied(caller.subject(), path, method, e.detail());
        SecurityAuditLogger.log(decision);
        throw e;
      }
    }

    // 2. Organization resource endpoints (/v1/organizations/{organizationId}...)
    Matcher orgMatcher = ORGANIZATION_PATH_PATTERN.matcher(path);
    if (orgMatcher.matches()) {
      String orgIdRaw = orgMatcher.group(1);
      OrganizationId targetOrgId;
      try {
        targetOrgId = OrganizationId.parse(orgIdRaw);
      } catch (IllegalArgumentException e) {
        AccessDeniedProblemException ex =
            new AccessDeniedProblemException(
                "Forbidden: Malformed organization identifier in request path");
        SecurityAuditLogger.log(
            SecurityDecision.denied(caller.subject(), path, method, ex.detail()));
        throw ex;
      }

      // Check if it's admin creation
      if (path.endsWith("/admins") && "POST".equalsIgnoreCase(method)) {
        try {
          PopulationAccessControl.assertDelegatedAdminCreation(caller, targetOrgId);
          SecurityDecision decision =
              SecurityDecision.permitted(
                  caller.subject(), path, method, "Delegated admin creation permitted");
          SecurityAuditLogger.log(decision);
          return;
        } catch (AccessDeniedProblemException e) {
          SecurityDecision decision =
              SecurityDecision.denied(caller.subject(), path, method, e.detail());
          SecurityAuditLogger.log(decision);
          throw e;
        }
      }

      // Task 9.5: crypto-shredding a participant's identity key is stricter than the general
      // organization-scoped access below -- an employer admin may capture identity data for their
      // own organization's participants, but destroying a key (right-to-erasure) is internal
      // staff/service only.
      if (path.endsWith("/identity") && "DELETE".equalsIgnoreCase(method)) {
        try {
          PopulationAccessControl.assertCanDestroyIdentityKey(caller);
          SecurityDecision decision =
              SecurityDecision.permitted(
                  caller.subject(), path, method, "Identity key destruction permitted");
          SecurityAuditLogger.log(decision);
          return;
        } catch (AccessDeniedProblemException e) {
          SecurityDecision decision =
              SecurityDecision.denied(caller.subject(), path, method, e.detail());
          SecurityAuditLogger.log(decision);
          throw e;
        }
      }

      try {
        PopulationAccessControl.assertOrganizationAccess(caller, targetOrgId);
        SecurityDecision decision =
            SecurityDecision.permitted(
                caller.subject(), path, method, "Organization access permitted");
        SecurityAuditLogger.log(decision);
        return;
      } catch (AccessDeniedProblemException e) {
        SecurityDecision decision =
            SecurityDecision.denied(caller.subject(), path, method, e.detail());
        SecurityAuditLogger.log(decision);
        throw e;
      }
    }

    // 3. Organization listing endpoint (/v1/organizations)
    if ("/v1/organizations".equals(path)) {
      if (caller.isParticipant() || caller.isEmployerAdmin()) {
        AccessDeniedProblemException e =
            new AccessDeniedProblemException(
                "Forbidden: Only internal staff or services can list organizations");
        SecurityDecision decision =
            SecurityDecision.denied(caller.subject(), path, method, e.detail());
        SecurityAuditLogger.log(decision);
        throw e;
      }
      SecurityDecision decision =
          SecurityDecision.permitted(caller.subject(), path, method, "Organization list permitted");
      SecurityAuditLogger.log(decision);
      return;
    }

    // 4. Privileged staff actions endpoints (/v1/staff/actions...)
    if (path.startsWith("/v1/staff/actions")) {
      if (!caller.isInternalStaff()) {
        AccessDeniedProblemException e =
            new AccessDeniedProblemException("Forbidden: Internal staff role required");
        SecurityDecision decision =
            SecurityDecision.denied(caller.subject(), path, method, e.detail());
        SecurityAuditLogger.log(decision);
        throw e;
      }
      SecurityDecision decision =
          SecurityDecision.permitted(
              caller.subject(), path, method, "Internal staff action endpoint permitted");
      SecurityAuditLogger.log(decision);
      return;
    }

    // 5. Funds management (/v1/funds...)
    if (path.startsWith("/v1/funds")) {
      if (caller.isInternalStaff() || caller.isInternalService()) {
        SecurityDecision decision =
            SecurityDecision.permitted(
                caller.subject(), path, method, "Fund operations permitted for staff/service");
        SecurityAuditLogger.log(decision);
        return;
      }
      AccessDeniedProblemException e =
          new AccessDeniedProblemException(
              "Forbidden: Fund operations require internal staff or service");
      SecurityDecision decision =
          SecurityDecision.denied(caller.subject(), path, method, e.detail());
      SecurityAuditLogger.log(decision);
      throw e;
    }

    // 6. Default Deny for any other endpoint
    AccessDeniedProblemException e =
        new AccessDeniedProblemException("Forbidden: Unrecognized or protected resource");
    SecurityDecision decision = SecurityDecision.denied(caller.subject(), path, method, e.detail());
    SecurityAuditLogger.log(decision);
    throw e;
  }

  private static String normalizePath(String rawPath) {
    try {
      URI uri = URI.create(rawPath).normalize();
      String path = uri.getPath();
      if (path == null) {
        return rawPath;
      }
      // Strip matrix parameters (;name=value) from every segment before any suffix match
      // (endsWith("/admins"), endsWith("/identity")) runs below. Spring's request mapping ignores
      // them when routing to a controller, so "/participants/{id}/identity;x=1" still reaches
      // ParticipantIdentityController#destroy even though it fails an unstripped endsWith check --
      // silently falling through to the more permissive organization-scoped rule instead of the
      // stricter one this class writes for that exact path shape.
      path = stripMatrixParameters(path);
      if (path.endsWith("/") && path.length() > 1) {
        return path.substring(0, path.length() - 1);
      }
      return path;
    } catch (Exception e) {
      return rawPath;
    }
  }

  private static String stripMatrixParameters(String path) {
    if (path.indexOf(';') < 0) {
      return path;
    }
    String[] segments = path.split("/", -1);
    StringBuilder result = new StringBuilder(path.length());
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        result.append('/');
      }
      String segment = segments[i];
      int semicolon = segment.indexOf(';');
      result.append(semicolon < 0 ? segment : segment.substring(0, semicolon));
    }
    return result.toString();
  }

  private static boolean isExemptPath(String path) {
    return path.startsWith("/actuator")
        || path.startsWith("/error")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/v3/api-docs");
  }
}
