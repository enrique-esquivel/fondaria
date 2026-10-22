package dev.fondaria.rules;

import java.util.Locale;

/**
 * Where a fund stands with the platform, mirroring {@code OrganizationStatus}'s three states for
 * the same reason: {@code CLOSED} is not a delete and never releases the fund's id or its unit
 * ledger, which continues to exist for as long as any participant holds units in it.
 */
public enum FundStatus {

  /** Open to new allotments. */
  ACTIVE,

  /** Temporarily barred from new allotments. Reversible. */
  SUSPENDED,

  /** No longer accepts new allotments. Existing positions are untouched. */
  CLOSED;

  public static FundStatus parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("status must be one of ACTIVE, SUSPENDED, CLOSED");
    }
  }
}
