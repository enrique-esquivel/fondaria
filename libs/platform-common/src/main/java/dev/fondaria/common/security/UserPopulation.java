package dev.fondaria.common.security;

/**
 * The three distinct user populations defined by Phase 9 identity architecture:
 *
 * <ul>
 *   <li>{@link #PARTICIPANT}: Employee viewing only their own positions, movements, and statements.
 *   <li>{@link #EMPLOYER_ADMIN}: HR/payroll administrator at one organization, scoped strictly to
 *       their bound organization.
 *   <li>{@link #INTERNAL_STAFF}: Operations and support staff with separated roles (support,
 *       operations, approver).
 * </ul>
 */
public enum UserPopulation {
  PARTICIPANT,
  EMPLOYER_ADMIN,
  INTERNAL_STAFF,
  INTERNAL_SERVICE
}
