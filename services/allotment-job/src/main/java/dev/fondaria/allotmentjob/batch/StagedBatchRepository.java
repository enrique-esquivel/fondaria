package dev.fondaria.allotmentjob.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code workflow.contribution_batch} and {@code workflow.contribution_batch_row}, the
 * two tables Task 3.6 staged, from allotment's side.
 *
 * <p>Locking is per batch, not per sweep: {@link #lockForAllotment} takes the row lock a single
 * batch's allotment is fired under, held for exactly as long as one batch's transaction — {@link
 * dev.fondaria.allotmentjob.allotment.AllotmentRunner} opens one transaction per candidate batch,
 * not one for the whole sweep, so N batches' ledger calls never share one held lock.
 */
public interface StagedBatchRepository {

  /**
   * Candidate batch ids to attempt this sweep, oldest submission first, unlocked. A plain read: no
   * lock is taken here, because enumerating candidates and claiming one are deliberately two
   * different statements — see {@link #lockForAllotment}.
   */
  List<UUID> stagedBatchIds(int limit);

  /**
   * Locks and returns one {@code STAGED} batch for allotment, within the caller's transaction —
   * {@code SELECT ... FOR UPDATE SKIP LOCKED}. Empty when the batch is no longer {@code STAGED} or
   * another instance already holds its lock, both of which mean "nothing to do here", never an
   * error.
   */
  Optional<StagedBatch> lockForAllotment(UUID batchId);

  /**
   * Every staged row for one batch of one organization — {@code workflow.contribution_batch_row},
   * ordered by {@code participant_internal_ref}.
   */
  List<StagedRow> rowsFor(OrganizationId organizationId, UUID batchId);

  /**
   * {@code STAGED -> ALLOTTED}, guarded by {@code status = 'STAGED'} the same way
   * contribution-api's {@code resolve} guards its own write-back. Returns {@code false} if the
   * guard did not hold — which should not happen given {@link #lockForAllotment}'s row lock covers
   * the same transaction, but the guard costs nothing and this method must never silently overwrite
   * a status it did not itself observe.
   */
  boolean markAllotted(UUID batchId, Instant allottedAt);
}
