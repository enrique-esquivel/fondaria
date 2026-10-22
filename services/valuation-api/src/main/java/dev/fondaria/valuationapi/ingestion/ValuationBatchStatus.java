package dev.fondaria.valuationapi.ingestion;

/** Where a submitted valuation batch stands. */
public enum ValuationBatchStatus {
  /** Submitted; the file has not been read yet. Submission returns {@code 202} at this point. */
  RECEIVED,

  /**
   * A validation pass holds this batch. Not terminal, and reclaimable once its lease goes stale.
   */
  VALIDATING,

  /**
   * Every row passed, and this batch's valuations are recorded in {@code workflow.valuation}.
   *
   * <p>Terminal for Task 20.3. <b>No NAV has been struck</b> — that is Task 20.4, which reads one
   * of these valuations, reads units in issue from the ledger, and divides.
   */
  ACCEPTED,

  /** A whole-batch failure. Terminal: no valuation in the file was accepted. */
  REJECTED;

  public static ValuationBatchStatus parse(String value) {
    return valueOf(value);
  }

  /** Whether the poller should look at this batch again. */
  public boolean isClaimable() {
    return this == RECEIVED || this == VALIDATING;
  }
}
