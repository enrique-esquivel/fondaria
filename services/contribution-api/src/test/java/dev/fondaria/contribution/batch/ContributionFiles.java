package dev.fondaria.contribution.batch;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.PlanVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;

/**
 * The worked example of {@code contribution-file-v2.md} §5 — the file and the submission that
 * declares it, which are now two separate things.
 *
 * <p>Reused rather than retyped per test so every test agrees on what a correct batch looks like,
 * and so a change to the format shows up as one failing helper rather than fifteen failing tests
 * that each disagree slightly.
 */
final class ContributionFiles {

  /** MXN 7,500.00, the wire the chart-of-accounts worked example posts. */
  static final long WORKED_EXAMPLE_TOTAL_CENTS = 750_000L;

  static final String PLAN_ID = "PLAN-ACME-CAJA-1";
  static final String PAYROLL_PERIOD = "2026-08-B";
  static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 20);

  /** §5's P-001: employed by Acme in 2019, enrolled in the plan seven years later. */
  static final LocalDate LONG_SERVICE_EMPLOYMENT_START = LocalDate.of(2019, 3, 1);

  /** §5's P-001 again: the enrolment date a service-based schedule must NOT be run from. */
  static final LocalDate LONG_SERVICE_ENROLMENT = LocalDate.of(2026, 1, 15);

  private ContributionFiles() {}

  /** §2's column-header row, from the parser itself so a fixture can never drift from it. */
  static String columnHeader() {
    return ContributionFileParser.columnHeaderLine();
  }

  /**
   * §5 exactly: two participants, 750000 cents, voluntary zero because caja de ahorro never uses
   * it.
   */
  static String workedExample() {
    return columnHeader()
        + "\n"
        + "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0\n"
        + "P-002,ACME-EMP-0891,2026-02-01,2026-02-01,200000,100000,0\n";
  }

  /** The declaration §5's submission carries, matching {@link #workedExample()} exactly. */
  static BatchDeclaration workedExampleDeclaration() {
    return new BatchDeclaration(
        PLAN_ID,
        PAYROLL_PERIOD,
        VALUE_DATE,
        WORKED_EXAMPLE_TOTAL_CENTS,
        2,
        Map.of(
            ContributionSource.EMPLOYEE,
            500_000L,
            ContributionSource.EMPLOYER,
            250_000L,
            ContributionSource.VOLUNTARY,
            0L));
  }

  /**
   * One participant, split employee/employer at the same 1:2 ratio (employer matches half the
   * employee amount) as {@link #workedExample()}'s own two rows — so a batch built from this row
   * satisfies {@code DEFAULT_PLAN_VERSION}'s matching ratio, the same as the worked example does.
   * Most callers reject the batch before that check ever runs (a wrong declared row count, for
   * instance); the few that don't need the split to hold exactly.
   */
  static String singleRow(long totalCents) {
    long employerCents = totalCents / 3;
    long employeeCents = totalCents - employerCents;
    return columnHeader()
        + "\nP-001,ACME-EMP-0142,2019-03-01,2026-01-15,"
        + employeeCents
        + ","
        + employerCents
        + ",0\n";
  }

  /** The declaration matching {@link #singleRow(long)}. */
  static BatchDeclaration singleRowDeclaration(long totalCents) {
    long employerCents = totalCents / 3;
    long employeeCents = totalCents - employerCents;
    return new BatchDeclaration(
        PLAN_ID,
        PAYROLL_PERIOD,
        VALUE_DATE,
        totalCents,
        1,
        Map.of(
            ContributionSource.EMPLOYEE, employeeCents,
            ContributionSource.EMPLOYER, employerCents));
  }

  /** Per-row split {@link #manyRows(int)} uses: 1:2 employer ratio, same as {@link #singleRow}. */
  private static final long MANY_ROWS_EMPLOYEE_CENTS = 20_000L;

  private static final long MANY_ROWS_EMPLOYER_CENTS = 10_000L;

  /**
   * {@code count} distinct participants, each contributing the same 1:2 employer ratio {@link
   * #defaultPlanVersion} enforces. {@link #workedExample()}'s two rows say nothing about
   * performance; Task 3.7's scale and soak tests need a file that actually has a size.
   */
  static String manyRows(int count) {
    StringBuilder file = new StringBuilder(columnHeader());
    for (int i = 1; i <= count; i++) {
      String ref = String.format("%06d", i);
      file.append('\n')
          .append("P-")
          .append(ref)
          .append(",PAYROLL-")
          .append(ref)
          .append(',')
          .append(LONG_SERVICE_EMPLOYMENT_START)
          .append(',')
          .append(LONG_SERVICE_ENROLMENT)
          .append(',')
          .append(MANY_ROWS_EMPLOYEE_CENTS)
          .append(',')
          .append(MANY_ROWS_EMPLOYER_CENTS)
          .append(",0");
    }
    file.append('\n');
    return file.toString();
  }

  /** The declaration matching {@link #manyRows(int)} exactly. */
  static BatchDeclaration manyRowsDeclaration(int count) {
    long employeeCents = MANY_ROWS_EMPLOYEE_CENTS * count;
    long employerCents = MANY_ROWS_EMPLOYER_CENTS * count;
    return new BatchDeclaration(
        PLAN_ID,
        PAYROLL_PERIOD,
        VALUE_DATE,
        employeeCents + employerCents,
        count,
        Map.of(
            ContributionSource.EMPLOYEE, employeeCents,
            ContributionSource.EMPLOYER, employerCents,
            ContributionSource.VOLUNTARY, 0L));
  }

  /** A declaration for a file this helper does not describe, for tests that vary one field. */
  static BatchDeclaration declaration(
      long totalCents, int rowCount, long employeeCents, long employerCents, long voluntaryCents) {
    return new BatchDeclaration(
        PLAN_ID,
        PAYROLL_PERIOD,
        VALUE_DATE,
        totalCents,
        rowCount,
        Map.of(
            ContributionSource.EMPLOYEE,
            employeeCents,
            ContributionSource.EMPLOYER,
            employerCents,
            ContributionSource.VOLUNTARY,
            voluntaryCents));
  }

  /**
   * The plan version {@link #workedExample()} and {@link #singleRow(long)} both satisfy: a 1:2
   * employer matching ratio (employer contributes half of the employee amount), effective well
   * before {@link #VALUE_DATE}. {@code vestingFullYears} is set but not exercised by anything in
   * this package — {@code VestingScheduleCalculatorTest} in {@code libs/rules-engine} owns that
   * assertion.
   *
   * @param fundId the fund this plan version allots into (ADR-0012). Callers going through the real
   *     {@code JdbcPlanVersionRepository} must register a matching {@code reference.fund} row first
   *     — {@code reference.plan_version.fund_id} is a foreign key; an in-memory repository has no
   *     such constraint and any id will do.
   */
  static PlanVersion defaultPlanVersion(OrganizationId organizationId, FundId fundId) {
    return new PlanVersion(
        organizationId,
        PLAN_ID,
        LocalDate.of(2020, 1, 1),
        1,
        1,
        2,
        5,
        0,
        fundId,
        Instant.parse("2020-01-01T00:00:00Z"));
  }

  /** The object-key prefix the service mints for a plan and period, for a test that submits one. */
  static String objectKey(OrganizationId organizationId, String fileName) {
    return "contribution-batches/"
        + organizationId.value()
        + "/"
        + PLAN_ID
        + "/"
        + PAYROLL_PERIOD
        + "/"
        + fileName;
  }

  static String sha256Hex(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
