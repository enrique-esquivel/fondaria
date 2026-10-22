package dev.fondaria.contribution.batch.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fondaria.contribution.batch.BatchPartProgress;
import dev.fondaria.contribution.batch.BatchRejection;
import dev.fondaria.contribution.batch.ContributionBatch;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * The batch status resource of the OpenAPI contract.
 *
 * <p><b>Every amount is a JSON integer of MXN cents.</b> Never a decimal, never a float, never a
 * string (AGENTS.md §4, restated by the contract's own description).
 *
 * <p><b>{@code awaitingAllotment} and {@code dealingDate} are the window, made visible.</b> Between
 * reconciliation and allotment a participant has money and no units — a first-class state ({@code
 * docs/DOMAIN.md} §5), not an inconsistency — and Task 21.1 requires a batch sitting in it be
 * reported as pending together with the dealing date it will receive. An employer asking "where is
 * my money" gets an answer with a date on it rather than a status that looks stuck. {@code
 * dealingDate} is absent, never null-and-present, whenever the batch is not waiting to deal.
 *
 * <p>The declared fields are present from submission onwards — {@code contribution-file-v2.md} §1.1
 * moved them out of the file — so a REJECTED batch says which payroll period was refused whatever
 * stage it failed at. Nothing here renders a participant reference: a batch status is about a file,
 * and per-row detail is Task 3.5's contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContributionBatchResponse(
    String batchId,
    String organizationId,
    String status,
    String objectKey,
    String checksumSha256,
    String planId,
    String payrollPeriod,
    String valueDate,
    long declaredTotalCents,
    int declaredRowCount,
    String submittedAt,
    String statusUpdatedAt,
    boolean awaitingAllotment,
    String dealingDate,
    Parts parts,
    Rejection rejection) {

  /**
   * @param details code-specific structured detail; amounts in it are integer cents.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Rejection(String code, String message, Map<String, Object> details) {}

  /**
   * Present only for a file that was divided (ADR-0017), and absent entirely for one that was not —
   * which is every file an employer sends today. A batch too large to allot as a single linked
   * chain becomes several batches, so its progress stops being one status and becomes a count, and
   * this is the only thing that tells an employer holding one id that the others exist.
   *
   * <p>It counts batches, never money. What has actually been priced is units in the ledger; this
   * says how much of the file has got there.
   *
   * @param fileBatchId the id the employer submitted and their wire funded — this batch itself when
   *     it is the file, its parent when it is a part
   * @param partNumber this batch's own 1-based position within the file
   * @param partCount how many batches the file was divided into
   * @param allottedParts how many have been priced. Short of {@code partCount} means a file still
   *     being allotted, which is a state and not a fault.
   */
  public record Parts(String fileBatchId, int partNumber, int partCount, int allottedParts) {}

  /**
   * @param dealingDate the valuation date this batch will deal at, from {@code DealingDateService}.
   *     Empty whenever the batch is not awaiting allotment — the two travel together, so the
   *     response can never claim a batch is pending while showing no date for it, nor show a date
   *     for a batch that has nothing to deal.
   */
  public static ContributionBatchResponse of(
      ContributionBatch batch, Optional<LocalDate> dealingDate) {
    return of(batch, dealingDate, Optional.empty());
  }

  /**
   * @param progress where this batch sits among its file's parts. Rendered only when the file was
   *     actually divided: a one-part file has nothing to explain, and emitting {@code "partCount":
   *     1} on every response would invite a reader to treat an ordinary batch as a fragment of
   *     something larger.
   */
  public static ContributionBatchResponse of(
      ContributionBatch batch,
      Optional<LocalDate> dealingDate,
      Optional<BatchPartProgress> progress) {
    return new ContributionBatchResponse(
        batch.batchId().toString(),
        batch.organizationId().toString(),
        batch.status().name(),
        batch.objectKey(),
        batch.checksumSha256(),
        batch.declared().planId(),
        batch.declared().payrollPeriod(),
        batch.declared().valueDate().toString(),
        batch.declared().declaredTotalCents(),
        batch.declared().declaredRowCount(),
        batch.submittedAt().toString(),
        batch.statusUpdatedAt().toString(),
        batch.isAwaitingAllotment(),
        dealingDate.map(LocalDate::toString).orElse(null),
        progress
            .filter(BatchPartProgress::isSplit)
            .map(ContributionBatchResponse::partsOf)
            .orElse(null),
        batch.rejection().map(ContributionBatchResponse::rejectionOf).orElse(null));
  }

  private static Parts partsOf(BatchPartProgress progress) {
    return new Parts(
        progress.fileBatchId().toString(),
        progress.partNumber(),
        progress.partCount(),
        progress.allottedParts());
  }

  private static Rejection rejectionOf(BatchRejection rejection) {
    return new Rejection(
        rejection.code().name(),
        rejection.message(),
        rejection.details().isEmpty() ? null : rejection.details());
  }
}
