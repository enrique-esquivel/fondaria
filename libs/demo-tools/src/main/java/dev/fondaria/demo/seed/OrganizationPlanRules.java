package dev.fondaria.demo.seed;

/**
 * The per-organization plan parameters `seed` chose for each of the 12 demo organizations,
 * expressed as pure functions of the organization index rather than persisted anywhere -- so {@code
 * contributions} can regenerate the same plan's rows for a later payroll period without {@code
 * demo/out/manifest.json} having to carry every plan field back out (`phase-D-demo.md` Task D.2).
 */
public final class OrganizationPlanRules {

  public static final int ORGANIZATION_COUNT = 12;

  /** Which of the 3 registered funds (index into that list) each organization uses. */
  private static final int[] FUND_INDEX_BY_ORGANIZATION = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2};

  /**
   * Dealing lag per organization -- mostly same-day (0), a few later, so most of the ~300,000
   * participants hold units immediately while a handful demonstrate the "awaiting allotment" state
   * that later demo beats can show settling.
   */
  private static final int[] DEALING_LAG_BY_ORGANIZATION = {0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 1};

  private static final int[][] MATCH_RATIOS = {{1, 1}, {1, 2}, {2, 3}, {1, 3}};

  private OrganizationPlanRules() {}

  public static int fundIndex(int organizationIndex) {
    return FUND_INDEX_BY_ORGANIZATION[organizationIndex];
  }

  public static int dealingLagBusinessDays(int organizationIndex) {
    return DEALING_LAG_BY_ORGANIZATION[organizationIndex];
  }

  public static int[] matchRatio(int organizationIndex) {
    return MATCH_RATIOS[organizationIndex % MATCH_RATIOS.length];
  }

  public static boolean isRetirementFund(String vehicleType) {
    return "RETIREMENT_FUND".equals(vehicleType);
  }
}
