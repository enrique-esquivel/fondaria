package dev.fondaria.valuationapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the asynchronous validator and the ageing report run.
 *
 * <p>Every value has a working default, unlike the connection settings elsewhere in this package.
 * These are operational tuning, not secrets or addresses: a deployment that sets none of them
 * ingests correctly, only on this cadence.
 *
 * @param pollInterval delay between validation sweeps
 * @param batchSize most batches one sweep claims
 * @param staleAfter how long a batch may sit in {@code VALIDATING} before another sweep may reclaim
 *     it. This is what makes a process dying mid-validation recoverable: the lease is a column, so
 *     any instance can pick the batch up, and the work is idempotent because re-validating a file
 *     from its own bytes reaches the same answer.
 * @param ageingWindowBusinessDates how far back the ageing report looks. Bounded because an
 *     unbounded walk would re-examine every business date since each fund's inception on every
 *     call, growing without limit and reporting absences from years ago that nobody is going to
 *     fill in.
 * @param ageingAlertBusinessDates how late a valuation must be before it is logged as a warning.
 *     One business date is normal — a provider delivering this morning's file this afternoon is not
 *     an incident — so the default gives a day of grace.
 * @param ageingAlertInterval how often the alerting sweep runs. An ageing report nobody opens is
 *     not a control, which is why this runs on a schedule rather than only on request.
 */
@ConfigurationProperties(prefix = "fondaria.valuation-ingestion")
public record ValuationIngestionProperties(
    Duration pollInterval,
    int batchSize,
    Duration staleAfter,
    int ageingWindowBusinessDates,
    int ageingAlertBusinessDates,
    Duration ageingAlertInterval) {

  public ValuationIngestionProperties {
    pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
    batchSize = batchSize <= 0 ? 10 : batchSize;
    staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
    ageingWindowBusinessDates = ageingWindowBusinessDates <= 0 ? 30 : ageingWindowBusinessDates;
    ageingAlertBusinessDates = ageingAlertBusinessDates <= 0 ? 2 : ageingAlertBusinessDates;
    ageingAlertInterval =
        ageingAlertInterval == null ? Duration.ofMinutes(15) : ageingAlertInterval;
  }
}
