package dev.fondaria.contribution.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A parsed contribution file — {@code contribution-file-v2.md} §1–§3.
 *
 * <p>A list of rows, because that is all the file is: a column-header row and a rectangle. What the
 * batch claims to be — plan, period, value date, totals — is not here. It arrives with the
 * submission as a {@link BatchDeclaration}, and {@link ContributionBatchValidator} is what compares
 * the two.
 *
 * <p>Amounts are keyed by {@link ContributionSource} rather than held in named fields so the source
 * vocabulary lives in one place — the enum and the parser's column order — instead of being
 * restated by every record and every loop that sums one.
 *
 * <p><b>Every amount is a {@code long} of MXN cents</b> (AGENTS.md §4). The file carries no
 * currency column because the platform is MXN-only, and no decimal point anywhere.
 */
public record ContributionFile(List<ParticipantRow> rows) {

  public ContributionFile {
    rows = List.copyOf(rows);
  }

  /**
   * One participant row (§3): who they are, when they joined, and what they contributed from each
   * source this period. The only data shape in the file.
   *
   * <p><b>The two dates are distinct and are never collapsed into one.</b> {@code
   * employmentStartDate} is when the employee joined the organization — the seniority clock a
   * graded vesting schedule runs off (ADR-0004) — and {@code enrolmentDate} is when they joined the
   * plan. An employee may work for years before the fund is offered to them, and under a
   * service-based schedule the first date can make them fully vested on their first contribution
   * while the second would not. Conflating them misallocates employer money at withdrawal, silently
   * and in either direction.
   *
   * <p>Both dates sit on the same row as the {@code EMPLOYER} amount they govern, so a vesting
   * computation reads one row and joins nothing.
   *
   * <p>Pseudonymous: two references, two dates and the amounts. No name, {@code RFC} or {@code
   * CURP} — those wait for Phase 16 and its crypto-shredding prerequisite.
   *
   * @param amounts every source present with an explicit value; {@code 0} means nothing from that
   *     source, and a row whose amounts are all zero never parses.
   */
  public record ParticipantRow(
      String participantInternalRef,
      String participantPayrollRef,
      LocalDate employmentStartDate,
      LocalDate enrolmentDate,
      Map<ContributionSource, Long> amounts) {

    public ParticipantRow {
      amounts = Map.copyOf(amounts);
    }

    public long amountOf(ContributionSource source) {
      return amounts.getOrDefault(source, 0L);
    }

    /**
     * Every field of §3 is person-identifying: two references, two dates and what they were paid.
     */
    @Override
    public String toString() {
      return "ParticipantRow[REDACTED]";
    }
  }
}
