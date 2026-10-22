package dev.fondaria.contribution.batch;

/**
 * Where a submitted batch stands.
 *
 * <p>Extends the OpenAPI contract's set on the same resource rather than introducing a parallel
 * one, exactly as the contract anticipates: Task 3.4 added {@link #PENDING_RECONCILIATION}, Task
 * 3.6 adds {@link #STAGED}, Task 21.2 adds {@link #ALLOTTED}.
 *
 * <p>{@link #ALLOTTED} is written by a different service — {@code allotment-job}, never this one —
 * but this enum has to know about it anyway: {@link #parse} is on the read path for every batch
 * this service's own status endpoint serves, and an allotted batch is read back through the same
 * {@code workflow.contribution_batch} row {@code allotment-job} updated. Falling behind the values
 * {@code workflow.contribution_batch}'s own {@code contribution_batch_status_known} CHECK
 * constraint allows (`V16__allotment.sql`) would turn a successfully allotted batch's own status
 * query into a 500.
 */
public enum ContributionBatchStatus {
  /** Submitted; the file has not been read yet. Submission returns {@code 202} at this point. */
  RECEIVED,

  /**
   * A validation pass holds this batch. Not terminal, and reclaimable once its lease goes stale.
   */
  VALIDATING,

  /**
   * Structurally sound, and its cash has not arrived — or has arrived in an amount that does not
   * equal the declared total. <b>Not a rejection and not terminal.</b> The file is fine; the money
   * is not there yet. Money and file are independent arrivals, so this state resolves itself when
   * the missing half turns up, and both halves appear on the ageing report until it does.
   */
  PENDING_RECONCILIATION,

  /**
   * Structurally sound, plan-rule validated (checks 3–8 of {@code contribution-file-v2.md} §4, Task
   * 3.5's parity rule among them) and fully funded: the cash claimed for this batch equals its
   * declared total to the cent. Nothing this service does waits between reaching this decision and
   * staging from it — {@link BatchValidationRunner} writes {@link #STAGED} directly, so a batch is
   * never durably found resting here.
   */
  VALIDATED,

  /**
   * Every row is recorded in {@code workflow.contribution_batch_row} with the batch's value date
   * (Task 3.6). Terminal for Phase 3 — "ready to allot"; allotment itself is Phase 21.
   */
  STAGED,

  /**
   * Every staged row has been priced at a struck NAV and posted to the ledger (Task 21.2, {@code
   * allotment-job}). Terminal: nothing further ever moves a batch out of this status.
   */
  ALLOTTED,

  /** A whole-batch structural failure. Terminal: nothing was staged, and nothing was allotted. */
  REJECTED;

  public static ContributionBatchStatus parse(String value) {
    return valueOf(value);
  }

  /** Whether the poller should look at this batch again. */
  public boolean isClaimable() {
    return this == RECEIVED || this == VALIDATING || this == PENDING_RECONCILIATION;
  }
}
