package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational tuning for the custodian-reconciliation pass (Task 7.3). Not secrets: every value has
 * a working default.
 *
 * @param pollInterval how often the scheduled pass runs
 * @param realBreakEscalationDays a real break (units or cash, unexplained by a pending trade)
 *     escalates once it has stayed open this many days
 * @param timingDifferenceEscalationDays a timing difference escalates on a longer threshold — a
 *     trade legitimately takes a day or more to settle, so ageing it out at the same rate as a real
 *     break would page on expected activity
 */
@ConfigurationProperties(prefix = "fondaria.custodian-reconciliation")
public record CustodianReconciliationProperties(
    Duration pollInterval, int realBreakEscalationDays, int timingDifferenceEscalationDays) {

  public CustodianReconciliationProperties {
    pollInterval = pollInterval == null ? Duration.ofHours(1) : pollInterval;
    realBreakEscalationDays = realBreakEscalationDays <= 0 ? 3 : realBreakEscalationDays;
    timingDifferenceEscalationDays =
        timingDifferenceEscalationDays <= 0 ? 5 : timingDifferenceEscalationDays;
  }
}
