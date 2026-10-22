package dev.fondaria.allotmentjob.allotment;

/**
 * Storage for {@code workflow.allotment_residual} (V17) — append-only, never updated or deleted.
 */
public interface AllotmentResidualRepository {

  /**
   * Records one run's residual. Idempotent on {@code batchId}: rerunning an interrupted allotment —
   * {@link AllotmentService#allot} recomputes the identical residual for the same batch — writes
   * nothing a second time.
   */
  void record(AllotmentResidual residual);
}
