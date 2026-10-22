package dev.fondaria.valuationapi.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage for {@code workflow.valuation_batch}. */
public interface ValuationBatchRepository {

  /**
   * Records a submission if this file has not been submitted before, and returns the batch either
   * way — the original one when it has.
   *
   * <p>This is the whole of duplicate suppression. There is no {@code Idempotency-Key} to compare
   * and no "same key, different body" to detect, because a batch's identity <i>is</i> its content:
   * {@code (source, objectKey, checksumSha256)}.
   */
  ValuationBatch submit(ValuationBatch batch);

  Optional<ValuationBatch> find(UUID batchId);

  /**
   * Claims up to {@code limit} batches for a validation pass and moves them to {@code VALIDATING}.
   *
   * <p>Claims newly {@code RECEIVED} batches, and {@code VALIDATING} ones whose lease predates
   * {@code staleBefore} — which is how a batch survives the process that was validating it dying.
   * Concurrency is {@code FOR UPDATE SKIP LOCKED}, so two instances sweep disjoint sets rather than
   * one blocking the other.
   */
  List<ValuationBatch> claimForValidation(Instant now, Instant staleBefore, int limit);

  /**
   * Writes a batch's outcome and clears its validation lease.
   *
   * <p>Guarded on the batch still being {@code VALIDATING}: a claim that went stale and was taken
   * by another pass must not have its result overwritten by the pass that lost it.
   *
   * @return whether this call wrote the outcome
   */
  boolean resolve(ValuationBatch batch);
}
