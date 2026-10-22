package dev.fondaria.demo.contribution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds a `contribution-file-v2.md` rectangle for one organization's payroll period
 * (`phase-D-demo.md` Task D.2), plus the batch declaration it must be submitted with.
 *
 * <p>Deterministic from {@code (seed, organizationIndex, participantIndex)}, so calling this again
 * for the same organization and period regenerates byte-identical rows without this module having
 * to persist a 300,000-row roster anywhere -- {@code demo/out/manifest.json} only remembers each
 * organization's employee count and plan.
 */
public final class ContributionFileGenerator {

  /**
   * `contribution-file-v2.md` §2, hand-kept in sync rather than imported -- this module does not
   * depend on {@code contribution-api} (its own precedent: {@code allotment-job} duplicates
   * contribution-api's staged-row shapes rather than importing its source tree).
   */
  public static final String COLUMN_HEADER =
      "participantInternalRef,participantPayrollRef,employmentStartDate,enrolmentDate,"
          + "employeeCents,employerCents,voluntaryCents";

  private static final long MIN_MONTHLY_SALARY_CENTS = 8_000_00L;
  private static final long SALARY_SPREAD_CENTS = 42_000_00L;

  /**
   * Deduction rate range 2%-5%, expressed in basis points out of {@link #BASIS_POINTS_SCALE} --
   * never a {@code double}, even for a rate this module only uses to generate a demo file
   * (docs/ENGINEERING-RULES.md §4: "NEVER double, float or BigDecimal for cash, units, NAV, rates
   * or returns").
   */
  private static final long MIN_DEDUCTION_BASIS_POINTS = 200;

  private static final long DEDUCTION_BASIS_POINTS_SPREAD = 300;
  private static final long BASIS_POINTS_SCALE = 10_000;

  /**
   * A fixed anchor for {@code employmentStartDate}, rather than an offset from each call's own
   * {@code valueDate} -- the same participant's seniority clock must not move later every payroll
   * period just because {@code contributions} is called again on a later date, or a graded vesting
   * schedule re-reading it across periods would see it drift. Comfortably before any date this demo
   * plausibly runs on, so {@code employmentStartDate ≤ enrolmentDate} always holds
   * (`contribution-file-v2.md` §4 check 2).
   */
  private static final LocalDate EMPLOYMENT_START_EPOCH = LocalDate.of(2010, 1, 1);

  private static final int EMPLOYMENT_START_SPREAD_DAYS = 14 * 365;

  private ContributionFileGenerator() {}

  public record GeneratedFile(
      String csv,
      int rowCount,
      long declaredTotalCents,
      Map<String, Long> declaredSourceCents,
      List<String> heroInternalRefs) {}

  /**
   * @param employmentRatioNumerator together with {@code employmentRatioDenominator}, the plan's
   *     employer-match ratio (`MatchingRatioRule`) -- every row's {@code employerCents} is computed
   *     to satisfy it exactly, so the generated file never trips the parity rule on its own account
   * @param heroCount how many of this organization's participants (from the front of the roster) to
   *     report back as heroes, for the manifest to remember by name
   */
  public static GeneratedFile generate(
      long seed,
      int organizationIndex,
      int participantCount,
      LocalDate valueDate,
      int employmentRatioNumerator,
      int employmentRatioDenominator,
      int heroCount) {
    Random random = new Random(seed * 1_000_003L + organizationIndex);
    StringBuilder csv = new StringBuilder(COLUMN_HEADER).append('\n');
    long totalEmployeeCents = 0;
    long totalEmployerCents = 0;
    List<String> heroes = new ArrayList<>();

    for (int i = 0; i < participantCount; i++) {
      String internalRef = "P-" + organizationIndex + "-" + i;
      String payrollRef = "PAYROLL-" + organizationIndex + "-" + i;
      // Anchored to a fixed epoch, not this call's valueDate, so the same participant's employment
      // start never moves just because a later payroll period regenerated their row -- a graded
      // vesting schedule needs it stable across periods (contribution-file-v2.md §3).
      LocalDate employmentStart =
          EMPLOYMENT_START_EPOCH.plusDays(random.nextInt(EMPLOYMENT_START_SPREAD_DAYS));
      LocalDate enrolmentDate = valueDate;

      long salaryCents = MIN_MONTHLY_SALARY_CENTS + random.nextLong(SALARY_SPREAD_CENTS);
      long deductionBasisPoints =
          MIN_DEDUCTION_BASIS_POINTS + random.nextLong(DEDUCTION_BASIS_POINTS_SPREAD);
      long employeeCents = employeeCentsFromSalary(salaryCents, deductionBasisPoints);
      long employerCents =
          expectedEmployerCents(
              employeeCents, employmentRatioNumerator, employmentRatioDenominator);

      csv.append(internalRef)
          .append(',')
          .append(payrollRef)
          .append(',')
          .append(employmentStart)
          .append(',')
          .append(enrolmentDate)
          .append(',')
          .append(employeeCents)
          .append(',')
          .append(employerCents)
          .append(",0\n");

      totalEmployeeCents += employeeCents;
      totalEmployerCents += employerCents;
      if (heroes.size() < heroCount) {
        heroes.add(internalRef);
      }
    }

    Map<String, Long> declaredSourceCents = new LinkedHashMap<>();
    declaredSourceCents.put("EMPLOYEE", totalEmployeeCents);
    declaredSourceCents.put("EMPLOYER", totalEmployerCents);
    declaredSourceCents.put("VOLUNTARY", 0L);

    return new GeneratedFile(
        csv.toString(),
        participantCount,
        totalEmployeeCents + totalEmployerCents,
        declaredSourceCents,
        heroes);
  }

  /**
   * A deliberately wrong grand total -- one cent short of the true sum -- so the batch fails
   * `DECLARED_TOTAL_MISMATCH` (`contribution-file-v2.md` §4 check 6) rather than a code specific to
   * how the corruption was introduced. Used by {@code --corrupt} to prove the validation gate is
   * on.
   */
  public static long corruptedDeclaredTotalCents(GeneratedFile file) {
    return file.declaredTotalCents() - 1;
  }

  /**
   * Half-up rounding of {@code salaryCents × deductionBasisPoints ÷ BASIS_POINTS_SCALE}, integer
   * only.
   */
  private static long employeeCentsFromSalary(long salaryCents, long deductionBasisPoints) {
    long numerator = Math.multiplyExact(salaryCents, deductionBasisPoints);
    long doubledNumerator = Math.multiplyExact(numerator, 2L);
    long doubledScale = BASIS_POINTS_SCALE * 2L;
    return Math.addExact(doubledNumerator, BASIS_POINTS_SCALE) / doubledScale;
  }

  /**
   * {@code MatchingRatioRule.expectedEmployerCents} -- half-up rounding of {@code employeeCents ×
   * numerator / denominator}, duplicated here rather than imported (this module depends on no
   * service or {@code libs/rules-engine} source).
   */
  private static long expectedEmployerCents(long employeeCents, int numerator, int denominator) {
    long numerator64 = Math.multiplyExact(employeeCents, (long) numerator);
    long doubledNumerator = Math.multiplyExact(numerator64, 2L);
    long doubledDenominator = Math.multiplyExact((long) denominator, 2L);
    return Math.addExact(doubledNumerator, denominator) / doubledDenominator;
  }
}
