package dev.fondaria.contribution.config;

import dev.fondaria.ledger.LedgerLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the asynchronous batch validator runs (ADR-0009: submission returns {@code 202} and
 * validation happens afterwards).
 *
 * <p>All three values have working defaults, unlike the connection settings elsewhere in this
 * package. They are operational tuning, not secrets or addresses: a deployment that sets none of
 * them validates correctly, only on this cadence.
 *
 * @param pollInterval delay between validation sweeps
 * @param batchSize most batches one sweep claims. Bounded so a backlog is worked through steadily
 *     rather than in one transaction holding a connection for minutes.
 * @param staleAfter how long a batch may sit in {@code VALIDATING} before another sweep may reclaim
 *     it. This is what makes a process dying mid-validation recoverable: the lease is a column, so
 *     any instance can pick the batch up, and the work is idempotent because re-validating a file
 *     from its own bytes reaches the same answer. Must comfortably exceed the time to fetch, parse
 *     and check the largest file expected, or a slow validation would be reclaimed underneath
 *     itself.
 * @param maxRowsPerPart most rows one part of a split file may hold (ADR-0017). Unlike the three
 *     above this is not purely operational: a part is allotted as one linked chain, so a value that
 *     is too large is not slow but refused outright by the ledger. It is bounded here rather than
 *     trusted — see the constructor — and the leg budget in {@code BatchPartitioner} bounds a part
 *     again, independently, because rows and transfers are not the same count.
 */
@ConfigurationProperties(prefix = "fondaria.batch-validation")
public record BatchValidationProperties(
    Duration pollInterval, int batchSize, Duration staleAfter, int maxRowsPerPart) {

  /**
   * 4,093 is the figure {@code TASK-LOG.md} step 40 measured the wall at: a file with an employee
   * and an employer lot on every row costs two legs per row, and {@code 4,093 × 2 + 2} is the
   * ledger's chain ceiling exactly. It is the default rather than the maximum because a file's real
   * cost depends on how many sources its rows carry, which is why {@code BatchPartitioner} counts
   * legs as well.
   */
  private static final int DEFAULT_MAX_ROWS_PER_PART = 4_093;

  public BatchValidationProperties {
    pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
    batchSize = batchSize <= 0 ? 10 : batchSize;
    staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
    // Clamped, not rejected. A misconfigured row cap must not be able to produce a part the
    // ledger will refuse: even one leg per row, the cheapest a row can be, cannot fit more
    // rows in a chain than there are legs in one.
    maxRowsPerPart = maxRowsPerPart <= 0 ? DEFAULT_MAX_ROWS_PER_PART : maxRowsPerPart;
    maxRowsPerPart = Math.min(maxRowsPerPart, LedgerLimits.MAX_LINKED_CHAIN_LENGTH - 2);
  }
}
