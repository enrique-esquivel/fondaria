package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When the internal reconciliation pass waits for the projection to catch up with the ledger
 * (ADR-0038). Operational tuning with working defaults, like {@link ReconciliationProperties}.
 *
 * @param quietPeriod {@code RECONCILIATION_CATCH_UP_QUIET_PERIOD} — a projection consumer that has
 *     not advanced for this long has nothing left to consume, so its row being behind the ledger's
 *     head does not mean its queue is. Must exceed the time between two of one consumer's batches
 *     while it is draining (milliseconds); defaults to 15 s.
 * @param stallAfter {@code RECONCILIATION_CATCH_UP_STALL_AFTER} — when no consumer has advanced for
 *     this long and the projection still has not reached the ledger's head, the pass runs anyway:
 *     the projection is stuck or the heads cannot be compared, and a failing run with its breaks is
 *     the evidence an operator needs. Defaults to 2 min.
 */
@ConfigurationProperties(prefix = "fondaria.reconciliation.catch-up")
public record ProjectionCatchUpProperties(Duration quietPeriod, Duration stallAfter) {

  public ProjectionCatchUpProperties {
    quietPeriod = quietPeriod == null ? Duration.ofSeconds(15) : quietPeriod;
    stallAfter = stallAfter == null ? Duration.ofMinutes(2) : stallAfter;
    if (quietPeriod.isNegative() || quietPeriod.isZero()) {
      throw new ConfigurationException(
          "RECONCILIATION_CATCH_UP_QUIET_PERIOD must be positive",
          "Set it to a duration such as 15s.");
    }
    if (stallAfter.compareTo(quietPeriod) <= 0) {
      throw new ConfigurationException(
          "RECONCILIATION_CATCH_UP_STALL_AFTER must exceed RECONCILIATION_CATCH_UP_QUIET_PERIOD",
          "A projection is only stalled once it has been quiet for longer than a drained consumer"
              + " is; set it to a duration such as 2m.");
    }
  }
}
