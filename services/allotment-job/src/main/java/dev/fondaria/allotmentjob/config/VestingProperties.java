package dev.fondaria.allotmentjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the vesting sweep runs — the same "operational tuning, not secrets" shape {@link
 * AllotmentProperties} is: an unset value still vests correctly, only on a different cadence.
 *
 * @param pollInterval delay between vesting sweeps
 * @param batchSize most pending (participant, fund) candidates one sweep evaluates
 */
@ConfigurationProperties(prefix = "fondaria.vesting")
public record VestingProperties(Duration pollInterval, int batchSize) {

  public VestingProperties {
    pollInterval = pollInterval == null ? Duration.ofMinutes(1) : pollInterval;
    batchSize = batchSize <= 0 ? 500 : batchSize;
  }
}
