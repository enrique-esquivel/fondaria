package dev.fondaria.contribution.batch;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What an employer says a batch is — {@code contribution-file-v2.md} §1.1.
 *
 * <p>These values used to be a header record inside the file. They describe the <b>batch</b> rather
 * than the participants, and they need typing, validation and rejection on their own terms, so they
 * arrive with the submission instead. The file is a rectangle of participants and nothing else.
 *
 * <p>Declared once, by the employer, and then compared against the file it declares: {@link
 * ContributionBatchValidator} checks the row count, the per-source totals and the grand total
 * against what actually parsed. A declaration that the file does not bear out rejects the batch.
 *
 * <p><b>Known at submission, not at parse.</b> That is a change worth noticing: a rejected batch
 * can now say which payroll period was refused, which it could not when these values only existed
 * once the file had been read.
 *
 * <p><b>{@link #declaredTotalCents()} is a declaration, not a balance.</b> What actually arrived is
 * read from the ledger, receipt by receipt, and the two are compared by the reconciliation gate —
 * never assumed equal, and this value is never one side of a balance (ADR-0008 invariant 1).
 *
 * @param declaredSourceCents one total per source, every source present with an explicit value; a
 *     source contributing nothing declares {@code 0} rather than being absent.
 */
public record BatchDeclaration(
    String planId,
    String payrollPeriod,
    LocalDate valueDate,
    long declaredTotalCents,
    int declaredRowCount,
    Map<ContributionSource, Long> declaredSourceCents) {

  public BatchDeclaration {
    Objects.requireNonNull(planId, "planId must not be null");
    Objects.requireNonNull(payrollPeriod, "payrollPeriod must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Map<ContributionSource, Long> copy = new EnumMap<>(ContributionSource.class);
    copy.putAll(declaredSourceCents);
    for (ContributionSource source : ContributionSource.values()) {
      copy.putIfAbsent(source, 0L);
    }
    declaredSourceCents = Map.copyOf(copy);
  }

  public long declaredCentsFor(ContributionSource source) {
    return declaredSourceCents.getOrDefault(source, 0L);
  }
}
