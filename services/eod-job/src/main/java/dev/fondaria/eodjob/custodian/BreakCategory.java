package dev.fondaria.eodjob.custodian;

/**
 * What kind of disagreement a {@link CustodianBreak} is, and therefore what scale {@code
 * driftScaled} is in and which ageing threshold applies.
 */
public enum BreakCategory {

  /** Units disagree and the statement's pending-trade figure does not explain it. */
  UNIT_BREAK(false),

  /** Cash disagrees and the statement's pending-trade figure does not explain it. */
  CASH_BREAK(false),

  /**
   * Units disagree exactly by the statement's own pending-trade figure — expected, not an error.
   */
  UNIT_TIMING_DIFFERENCE(true),

  /** The cash-side counterpart of {@link #UNIT_TIMING_DIFFERENCE}. */
  CASH_TIMING_DIFFERENCE(true);

  private final boolean timingDifference;

  BreakCategory(boolean timingDifference) {
    this.timingDifference = timingDifference;
  }

  /** Whether this category is expected trade-settlement lag rather than a real disagreement. */
  public boolean isTimingDifference() {
    return timingDifference;
  }
}
