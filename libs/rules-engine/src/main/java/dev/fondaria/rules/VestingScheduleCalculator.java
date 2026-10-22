package dev.fondaria.rules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A linear graded vesting schedule, run off a participant's service start date — the case phase-02b
 * Task 2b.3's acceptance criterion 5 exists to catch: a participant enrolled years after their
 * employment start must be fully vested when the schedule runs off {@code employmentStartDate}, and
 * not yet when it is (wrongly) run off {@code enrolmentDate} (ADR-0004, phase-02b Task 2b.3).
 *
 * <p>Vested fraction is expressed in basis points (10000 = 100%), the same fixed-point convention
 * {@code Units}/{@code Nav} use elsewhere in the platform — never {@code double} or {@code
 * BigDecimal} (docs/ENGINEERING-RULES.md §4).
 *
 * <p>Only a linear grade to {@code vestingFullYears} is implemented. Cliffs, non-linear grades and
 * forfeiture on termination are open questions this step does not answer (phase-02b "Open
 * questions").
 */
public final class VestingScheduleCalculator {

  private static final long FULL_BASIS_POINTS = 10_000L;

  private VestingScheduleCalculator() {}

  /**
   * @param plan carries {@link PlanVersion#vestingFullYears()} — years of continuous service at
   *     which vesting reaches 100%. Zero means immediate vesting.
   * @param serviceStart the date service is measured from — {@code employmentStartDate} for a
   *     correct schedule, {@code enrolmentDate} for the incorrect one criterion 5 guards against.
   * @param asOf the date vesting is evaluated as of.
   * @return the vested fraction in basis points, clamped to {@code [0, 10000]}.
   */
  public static long vestedBasisPoints(PlanVersion plan, LocalDate serviceStart, LocalDate asOf) {
    if (plan.vestingFullYears() == 0) {
      return asOf.isBefore(serviceStart) ? 0L : FULL_BASIS_POINTS;
    }
    if (asOf.isBefore(serviceStart)) {
      return 0L;
    }
    long totalDays =
        ChronoUnit.DAYS.between(serviceStart, serviceStart.plusYears(plan.vestingFullYears()));
    long elapsedDays = ChronoUnit.DAYS.between(serviceStart, asOf);
    if (elapsedDays >= totalDays) {
      return FULL_BASIS_POINTS;
    }
    return Math.multiplyExact(elapsedDays, FULL_BASIS_POINTS) / totalDays;
  }
}
