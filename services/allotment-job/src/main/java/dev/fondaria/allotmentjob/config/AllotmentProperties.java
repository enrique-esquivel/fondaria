package dev.fondaria.allotmentjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the allotment sweep runs.
 *
 * <p>All values have working defaults, unlike the connection settings elsewhere in this package.
 * They are operational tuning, not secrets or addresses: a deployment that sets none of them allots
 * correctly, only on this cadence.
 *
 * @param pollInterval delay between allotment sweeps
 * @param batchSize most STAGED batches one sweep claims. A batch is fully allotted or not attempted
 *     at all within one sweep's transaction (Task 21.2: "one linked chain, fired atomically"), so
 *     this bounds how many batches — not rows — one sweep processes.
 */
@ConfigurationProperties(prefix = "fondaria.allotment")
public record AllotmentProperties(Duration pollInterval, int batchSize) {

  public AllotmentProperties {
    pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
    batchSize = batchSize <= 0 ? 200 : batchSize;
  }
}
