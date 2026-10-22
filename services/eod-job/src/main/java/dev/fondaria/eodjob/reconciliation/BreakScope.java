package dev.fondaria.eodjob.reconciliation;

/**
 * What a break is about, and therefore what scale its amounts are in — units at 1e-8 for a fund,
 * MXN cents for a cash account. The scale is a property of the scope, which is why one amount field
 * serves both.
 */
public enum BreakScope {
  FUND_UNITS,
  CASH_ACCOUNT
}
