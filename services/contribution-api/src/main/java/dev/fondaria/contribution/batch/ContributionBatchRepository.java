package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for {@code workflow.contribution_batch}.
 *
 * <p>An interface for the same reason {@code CashReceiptRepository} is one: the decisions worth
 * testing — what the gate does, what the validator concludes, which status a failure lands on —
 * belong above this line and should be provable without a database in the loop. The one JDBC
 * implementation proves the SQL separately, against a real PostgreSQL.
 */
public interface ContributionBatchRepository {

  /**
   * Records a submission if this file has not been submitted before, and returns the batch either
   * way — the original one when it has.
   *
   * <p>This is the whole of duplicate suppression for submissions. There is no {@code
   * Idempotency-Key} header to compare and no "same key, different body" to detect, because the
   * batch's identity <i>is</i> its content: {@code (organizationId, objectKey, checksumSha256)}.
   */
  ContributionBatch submit(ContributionBatch batch);

  Optional<ContributionBatch> find(OrganizationId organizationId, UUID batchId);

  /**
   * Records one part of a file too large to allot as a single linked chain (ADR-0017), if that part
   * has not been recorded before.
   *
   * <p>Idempotent on the part's derived id, so staging a file twice — a stale lease, a crash and a
   * retry — converges on the parts that already exist rather than creating a second set.
   *
   * @param part the part as a batch in its own right, {@code PENDING_RECONCILIATION}: structurally
   *     valid, and waiting on its file's cash rather than on any of its own
   * @param fileBatchId the submitted batch this is a part of. It is what excludes the part from the
   *     validation sweep, the reconciliation gate and the ageing report, all three of which look at
   *     files alone.
   * @param partNumber 1-based position within the file, always 2 or more — the submitted batch is
   *     part 1 and keeps its own rows
   * @return whether this call created the part
   */
  boolean insertPartIfAbsent(ContributionBatch part, UUID fileBatchId, int partNumber);

  /**
   * Moves every part of a file to {@code STAGED}, stamping the same {@code stagedAt} the file
   * itself received.
   *
   * <p><b>Must be called in the transaction that stages the file</b>, and is what makes "the
   * employer funds once" true: a part has no cash of its own, so the file's reconciliation is the
   * only event that can make its rows dealable. A file staged with its parts left behind would hold
   * cash for rows nothing would ever allot.
   *
   * <p>Write-once on {@code staged_at}, exactly as {@link #resolve} is, so a re-resolve cannot
   * re-date a part that has been waiting to deal; and confined to parts still waiting, so a part
   * already allotted is untouched.
   *
   * @return how many parts this call moved. Zero for a file that was never split.
   */
  int stageParts(UUID fileBatchId, Instant stagedAt, Instant statusUpdatedAt);

  /**
   * Where one batch sits within its file's parts, for a batch that may be a file or a part of one.
   *
   * <p>This is how a split file is followed at all: its progress is not one status but its parts'
   * statuses, and an employer holding the id it submitted needs to be told there are others and how
   * many have been priced.
   *
   * @return empty when no such batch exists for this organization. A file that was never split
   *     reports a count of one, which is the truthful answer and lets the caller omit the whole
   *     notion rather than special-case it.
   */
  Optional<BatchPartProgress> progressOf(OrganizationId organizationId, UUID batchId);

  /**
   * Claims up to {@code limit} batches for a validation pass and moves them to {@code VALIDATING}.
   *
   * <p>Claims three kinds of batch: newly {@code RECEIVED} ones; {@code PENDING_RECONCILIATION}
   * ones, whose cash may have arrived since the last pass; and {@code VALIDATING} ones whose lease
   * predates {@code staleBefore}, which is how a batch survives the process that was validating it
   * dying. Concurrency is {@code FOR UPDATE SKIP LOCKED}, so two instances sweep disjoint sets
   * rather than one blocking the other.
   *
   * <p><b>Files only, never parts</b> (ADR-0017). A part has already passed every structural check
   * its file passed and has no cash of its own to reconcile, so there is nothing for a sweep to do
   * with one; claiming parts would also let fifty of them fill a sweep's limit and starve the files
   * queued behind them.
   */
  List<ContributionBatch> claimForValidation(Instant now, Instant staleBefore, int limit);

  /**
   * Writes a batch's outcome and clears its validation lease.
   *
   * <p>Guarded on the batch still being {@code VALIDATING}: a claim that went stale and was taken
   * by another pass must not have its result overwritten by the pass that lost it.
   *
   * @return whether this call wrote the outcome
   */
  boolean resolve(ContributionBatch batch);

  /**
   * Persists the fact that this batch has passed structural and plan-rule validation and has been
   * staged, before reconciliation begins.
   *
   * <p>Guarded on {@code status = 'VALIDATING'}: if the lease expired and another sweep claimed it,
   * this call returns {@code false} so the current sweep halts rather than continuing to reconcile
   * under a lost lease.
   *
   * @return whether the batch was updated
   */
  boolean markFileValidated(UUID batchId, Instant fileValidatedAt);

  /**
   * Releases a validation lease on transient failure so the batch can be retried immediately on the
   * next sweep rather than staying locked in {@code VALIDATING} until {@code staleAfter} expires.
   *
   * <p>Moves the batch back to {@code PENDING_RECONCILIATION} if {@code file_validated_at} was
   * already persisted, or {@code RECEIVED} if the failure occurred before structural validation
   * completed.
   *
   * @return whether the lease was released
   */
  boolean releaseValidation(UUID batchId, Instant now);

  /**
   * Batches whose cash has not arrived — the file half of the ageing report.
   *
   * <p>Files only, never parts (ADR-0017): a part is waiting on its file's wire, not on one of its
   * own, and listing all fifty of them would report one outstanding remittance as fifty.
   */
  List<ContributionBatch> pendingReconciliation(OrganizationId organizationId);
}
