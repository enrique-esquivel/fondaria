package dev.fondaria.common.security;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The verified identity of the authenticated caller making a request into the platform.
 *
 * <p>Carries:
 *
 * <ul>
 *   <li>{@code subject}: The unique subject identifier (token {@code sub}).
 *   <li>{@code population}: Which of the three populations (Participant, Employer Admin, Internal
 *       Staff).
 *   <li>{@code roles}: Set of granted roles from token claims.
 *   <li>{@code organizationId}: Scoped organization id for employer admins, bound at provisioning.
 *   <li>{@code participantId}: Resolved participant id for participants.
 * </ul>
 */
public record CallerIdentity(
    String subject,
    UserPopulation population,
    Set<String> roles,
    Optional<OrganizationId> organizationId,
    Optional<ParticipantId> participantId) {

  public CallerIdentity {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(population, "population");
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(participantId, "participantId");
    roles = Collections.unmodifiableSet(new HashSet<>(roles != null ? roles : Set.of()));
  }

  public boolean isParticipant() {
    return population == UserPopulation.PARTICIPANT;
  }

  public boolean isEmployerAdmin() {
    return population == UserPopulation.EMPLOYER_ADMIN;
  }

  public boolean isInternalStaff() {
    return population == UserPopulation.INTERNAL_STAFF;
  }

  public boolean hasRole(String role) {
    return role != null && roles.contains(role);
  }

  public boolean hasStaffRole(StaffRole role) {
    if (role == null) {
      return false;
    }
    return roles.contains(role.roleName()) || roles.contains(role.name());
  }

  public static CallerIdentity participant(ParticipantId participantId) {
    Objects.requireNonNull(participantId, "participantId");
    return new CallerIdentity(
        participantId.toString(),
        UserPopulation.PARTICIPANT,
        Set.of("participant"),
        Optional.empty(),
        Optional.of(participantId));
  }

  public static CallerIdentity participant(String subject) {
    Objects.requireNonNull(subject, "subject");
    Optional<ParticipantId> pId = Optional.empty();
    try {
      pId = Optional.of(ParticipantId.parse(subject));
    } catch (IllegalArgumentException ignored) {
      // subject might be a distinct opaque identifier
    }
    return new CallerIdentity(
        subject, UserPopulation.PARTICIPANT, Set.of("participant"), Optional.empty(), pId);
  }

  public static CallerIdentity employerAdmin(String subject, OrganizationId organizationId) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(organizationId, "organizationId");
    return new CallerIdentity(
        subject,
        UserPopulation.EMPLOYER_ADMIN,
        Set.of("employer-admin"),
        Optional.of(organizationId),
        Optional.empty());
  }

  public static CallerIdentity internalStaff(String subject, StaffRole... staffRoles) {
    Objects.requireNonNull(subject, "subject");
    Set<String> roleSet = new HashSet<>();
    roleSet.add("internal-staff");
    if (staffRoles != null) {
      for (StaffRole r : staffRoles) {
        if (r != null) {
          roleSet.add(r.roleName());
          roleSet.add(r.name());
        }
      }
    }
    return new CallerIdentity(
        subject, UserPopulation.INTERNAL_STAFF, roleSet, Optional.empty(), Optional.empty());
  }

  public boolean isInternalService() {
    return population == UserPopulation.INTERNAL_SERVICE;
  }

  public static CallerIdentity internalService(String serviceName) {
    Objects.requireNonNull(serviceName, "serviceName");
    return new CallerIdentity(
        serviceName,
        UserPopulation.INTERNAL_SERVICE,
        Set.of("internal-service"),
        Optional.empty(),
        Optional.empty());
  }
}
