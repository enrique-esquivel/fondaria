package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational tuning for the dense daily snapshot (Task 7.4). Not secrets: every value has a
 * working default, and a deployment that sets none of them snapshots correctly on this cadence. The
 * bucket and its credentials are separate, and are secrets — see {@link ObjectStoreProperties}.
 *
 * @param pollInterval how often the scheduled pass runs. The close needs one snapshot a day; a
 *     shorter interval re-derives a file that has not changed, finds the same hash and stores
 *     nothing, which is cheap and is also the re-run check exercising itself.
 * @param holdingPageSize how many holdings are drawn from {@code workflow.allotment} per page. This
 *     is the unit of work for all three reads — the keyset scan, the units query and the cost-basis
 *     range — so it bounds memory rather than correctness. Smaller than internal reconciliation's
 *     page because each holding here carries lots and a position, not just a key.
 * @param accountLookupBatchSize how many account ids are read from the ledger per request. Bounded
 *     by what one ledger message body holds, the same ceiling {@link ReconciliationProperties}
 *     documents. Only {@code UNITS_IN_ISSUE} is read here — one account per fund — so it is
 *     effectively never reached; it is bounded anyway because a truncated read looks exactly like a
 *     fund with nothing in issue, which would turn a silent short read into a false tie.
 * @param keyPrefix the prefix every snapshot object is stored under, before {@code
 *     /dt=YYYY-MM-DD/}. Configuration rather than a constant so one bucket can hold more than one
 *     environment's output without either being able to name the other's key.
 * @param maximumObjectBytes the largest compressed positions file this pass will build. The file is
 *     assembled in memory (see {@code PositionFileWriter}), so this is the memory bound; past it
 *     the pass fails naming both numbers rather than dying of an {@code OutOfMemoryError} that says
 *     nothing about which step was running.
 */
@ConfigurationProperties(prefix = "fondaria.snapshot")
public record SnapshotProperties(
    Duration pollInterval,
    int holdingPageSize,
    int accountLookupBatchSize,
    String keyPrefix,
    long maximumObjectBytes) {

  /** The ledger's own per-request ceiling, mirroring {@link ReconciliationProperties}. */
  public static final int MAX_ACCOUNT_LOOKUP_BATCH = 8_189;

  /**
   * The largest file the in-memory writer can hold: a {@code byte[]}, so {@code Integer.MAX_VALUE}.
   * Not a tuning choice — a hard property of how the file is built.
   */
  public static final long MAX_OBJECT_BYTES = Integer.MAX_VALUE;

  public SnapshotProperties {
    pollInterval = pollInterval == null ? Duration.ofHours(1) : pollInterval;
    holdingPageSize = holdingPageSize <= 0 ? 10_000 : holdingPageSize;
    accountLookupBatchSize = accountLookupBatchSize <= 0 ? 8_189 : accountLookupBatchSize;
    keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "snapshots" : keyPrefix.strip();
    maximumObjectBytes = maximumObjectBytes <= 0 ? 1_073_741_824L : maximumObjectBytes;
    if (accountLookupBatchSize > MAX_ACCOUNT_LOOKUP_BATCH) {
      throw new ConfigurationException(
          "SNAPSHOT_ACCOUNT_LOOKUP_BATCH exceeds what one ledger request can carry ("
              + MAX_ACCOUNT_LOOKUP_BATCH
              + ")",
          "Lower it to "
              + MAX_ACCOUNT_LOOKUP_BATCH
              + " or less. A larger batch is not split — the ledger would answer part of it, and a"
              + " partial answer is indistinguishable from a fund holding nothing in issue, which"
              + " would let an incomplete file tie.");
    }
    if (keyPrefix.startsWith("/") || keyPrefix.endsWith("/")) {
      throw new ConfigurationException(
          "SNAPSHOT_KEY_PREFIX must not begin or end with '/'",
          "Write it as a bare path segment, for example 'snapshots' or 'eod/snapshots'. The"
              + " date partition and filename are appended to it.");
    }
    if (maximumObjectBytes > MAX_OBJECT_BYTES) {
      // SnapshotTooLargeException tells an operator to raise this value, so it has to be
      // impossible to raise it into a worse failure. The file is accumulated in a
      // ByteArrayOutputStream, whose backing array cannot exceed Integer.MAX_VALUE — a larger
      // ceiling would turn the bounded, self-explaining failure into an OutOfMemoryError partway
      // through a close, which says nothing about which step was running.
      throw new ConfigurationException(
          "SNAPSHOT_MAX_OBJECT_BYTES exceeds the largest file this pass can hold ("
              + MAX_OBJECT_BYTES
              + ")",
          "Lower it to "
              + MAX_OBJECT_BYTES
              + " or less. The snapshot is built in memory, so the JVM also needs roughly three"
              + " times this value of headroom while the buffer grows. A book that genuinely"
              + " outgrows it needs the file spooled to storage rather than a larger number here.");
    }
  }
}
