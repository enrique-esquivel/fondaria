package dev.fondaria.eodjob.custodian;

/**
 * Omnibus vs. segregated custody, as an attribute of one {@link ProviderAccount} rather than a
 * platform-wide mode ({@code ADR-0019}).
 */
public enum ProviderScopeType {

  /** Exactly one account per fund, covering every participant. What v1 ships. */
  ALL_PARTICIPANTS,

  /**
   * One account per fund per employer. Not a live integration in v1 — proven by {@code
   * CustodianReconciliationOrganizationScopeTest}, not offered through any ingestion path yet.
   */
  ORGANIZATION
}
