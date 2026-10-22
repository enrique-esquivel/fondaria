package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational tuning for the internal reconciliation pass. Not secrets: every value has a working
 * default, and a deployment that sets none of them reconciles correctly on this cadence.
 *
 * @param pollInterval how often the scheduled pass runs. The daily close needs one pass a day; a
 *     shorter interval simply re-ties a book that has not changed, which is cheap and harmless.
 * @param accountLookupBatchSize how many account ids are read from the ledger per request. Bounded
 *     by what one ledger message body holds — {@code LedgerLimits.MAX_LINKED_CHAIN_LENGTH}, the
 *     same 8,189 that bounds a linked chain (ADR-0017), because it is the same megabyte. A larger
 *     value is rejected at startup rather than truncated silently, because a truncated read looks
 *     exactly like a fund whose participants hold nothing.
 * @param holdingPageSize how many holding-account keys are drawn from {@code workflow.allotment}
 *     per query. Keyset-paged, so this bounds memory rather than correctness.
 */
@ConfigurationProperties(prefix = "fondaria.reconciliation")
public record ReconciliationProperties(
    Duration pollInterval, int accountLookupBatchSize, int holdingPageSize) {

  /**
   * The ledger's own per-request ceiling. Held as a literal rather than read from {@code
   * LedgerLimits} so this record stays a plain binding target with no ledger dependency; {@code
   * ReconciliationPropertiesTest} asserts the two agree.
   */
  public static final int MAX_ACCOUNT_LOOKUP_BATCH = 8_189;

  public ReconciliationProperties {
    pollInterval = pollInterval == null ? Duration.ofHours(1) : pollInterval;
    accountLookupBatchSize = accountLookupBatchSize <= 0 ? 8_189 : accountLookupBatchSize;
    holdingPageSize = holdingPageSize <= 0 ? 50_000 : holdingPageSize;
    if (accountLookupBatchSize > MAX_ACCOUNT_LOOKUP_BATCH) {
      throw new ConfigurationException(
          "RECONCILIATION_ACCOUNT_LOOKUP_BATCH exceeds what one ledger request can carry ("
              + MAX_ACCOUNT_LOOKUP_BATCH
              + ")",
          "Lower it to "
              + MAX_ACCOUNT_LOOKUP_BATCH
              + " or less. A larger batch is not split — the ledger would answer part of it, and a"
              + " partial answer is indistinguishable from participants holding nothing.");
    }
  }
}
