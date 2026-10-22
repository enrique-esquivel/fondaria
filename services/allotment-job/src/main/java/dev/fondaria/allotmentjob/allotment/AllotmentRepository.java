package dev.fondaria.allotmentjob.allotment;

import java.util.List;

/** Storage for {@code workflow.allotment} (V16) — append-only, never updated or deleted. */
public interface AllotmentRepository {

  /**
   * Records every allotment produced for one batch. Idempotent on {@code (organizationId, batchId,
   * participantInternalRef, source)}: rerunning an interrupted allotment — the ledger chain having
   * already landed, or having just landed again on the same derived ids — writes nothing a second
   * time.
   */
  void recordAll(List<AllotmentRecord> allotments);
}
