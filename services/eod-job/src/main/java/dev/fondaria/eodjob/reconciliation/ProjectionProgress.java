package dev.fondaria.eodjob.reconciliation;

import java.time.Duration;
import java.util.Optional;

/**
 * How far the CDC-fed projection has committed, read from {@code
 * projection.projection_consumer_checkpoint} (ADR-0032 §1, ADR-0038).
 *
 * <p>A progress estimate only: the checkpoint is not a gap-free watermark, and nothing here decides
 * whether the book ties. It only lets the close skip a pass that could not tie yet.
 */
public interface ProjectionProgress {

  /**
   * The projection's committed progress measured against {@code ledgerHeadNanos}.
   *
   * @param ledgerHeadNanos the ledger's latest transfer timestamp, in nanoseconds
   * @param quietPeriod a consumer that has not advanced within this is treated as drained
   */
  Snapshot against(long ledgerHeadNanos, Duration quietPeriod);

  /**
   * @param highestCommittedNanos the highest ledger timestamp any consumer has committed; empty
   *     when no consumer has committed anything
   * @param busyConsumersBehind consumers behind the ledger's head that advanced within the quiet
   *     period — still draining a queue
   * @param sinceLastAdvance how long since any consumer last advanced; empty with no consumers
   */
  record Snapshot(
      Optional<Long> highestCommittedNanos,
      long busyConsumersBehind,
      Optional<Duration> sinceLastAdvance) {

    public Snapshot {
      java.util.Objects.requireNonNull(highestCommittedNanos, "highestCommittedNanos");
      java.util.Objects.requireNonNull(sinceLastAdvance, "sinceLastAdvance");
    }
  }
}
