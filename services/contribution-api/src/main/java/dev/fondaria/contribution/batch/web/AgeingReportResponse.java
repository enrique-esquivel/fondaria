package dev.fondaria.contribution.batch.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fondaria.contribution.batch.ReconciliationAgeingService;
import java.util.List;

/**
 * The reconciliation ageing report: unmatched cash on one side, unmatched files on the other.
 *
 * <p><b>The two sides are never added together and no grand total is rendered.</b> One is money the
 * ledger holds; the other is money an employer says it sent. A single number combining them would
 * read as a balance, and it would be a wrong one.
 *
 * <p>Every amount is a JSON integer of MXN cents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgeingReportResponse(
    String organizationId, String asOf, List<Cash> unmatchedCash, List<File> unmatchedFiles) {

  /**
   * @param amountCents read from the ledger. Null when the ledger does not hold the movement this
   *     receipt claims — reported rather than hidden, because it is the item an operator most needs
   *     to see.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cash(String transferId, String valueDate, Long amountCents, int ageDays) {}

  /**
   * @param declaredTotalCents what the file declared. A declaration, not a balance.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record File(
      String batchId,
      String payrollPeriod,
      String valueDate,
      long declaredTotalCents,
      String submittedAt,
      int ageDays) {}

  public static AgeingReportResponse of(ReconciliationAgeingService.AgeingReport report) {
    return new AgeingReportResponse(
        report.organizationId().toString(),
        report.asOf().toString(),
        report.unmatchedCash().stream()
            .map(
                cash ->
                    new Cash(
                        cash.transferId().toString(),
                        cash.valueDate().toString(),
                        cash.amountCents().orElse(null),
                        cash.ageDays()))
            .toList(),
        report.unmatchedFiles().stream()
            .map(
                file ->
                    new File(
                        file.batchId().toString(),
                        file.payrollPeriod(),
                        file.valueDate() == null ? null : file.valueDate().toString(),
                        file.declaredTotalCents(),
                        file.submittedAt().toString(),
                        file.ageDays()))
            .toList());
  }
}
