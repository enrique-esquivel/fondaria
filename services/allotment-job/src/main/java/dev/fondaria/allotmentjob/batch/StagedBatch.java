package dev.fondaria.allotmentjob.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code workflow.contribution_batch} row in {@code STAGED} status, as much of it as the
 * allotment run needs to price and post it.
 *
 * <p>Narrower than contribution-api's own {@code ContributionBatch}: no object key, checksum,
 * rejection, declared totals or file-validation timestamp — this service never re-derives a
 * decision contribution-api already reached, only reads what {@link
 * dev.fondaria.allotmentjob.allotment.AllotmentService} needs to price the batch: which plan
 * resolves its fund, and the two inputs {@code DealingDateRule} needs to independently re-derive
 * the exact dealing date {@code DealingDateService} already promised this batch when it staged.
 *
 * @param valueDate the batch's declared value date — {@code DealingDateRule}'s {@code valueDate}
 *     input
 * @param stagedAt when this batch first reached {@code STAGED} (Task 21.1, write-once) — {@code
 *     DealingDateRule}'s {@code readyAt} input. Always present: only a {@code STAGED} batch is ever
 *     read by this repository, and {@code JdbcContributionBatchRepository.resolve} writes {@code
 *     staged_at} in the same statement that writes {@code STAGED} — the one gap, a batch staged
 *     before Task 21.1's migration existed, is already excluded by {@link
 *     dev.fondaria.allotmentjob.batch.JdbcStagedBatchRepository}'s query.
 */
public record StagedBatch(
    UUID batchId,
    OrganizationId organizationId,
    String planId,
    LocalDate valueDate,
    Instant stagedAt) {

  public StagedBatch {
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(planId, "planId must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(stagedAt, "stagedAt must not be null");
  }
}
