package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerRejectionReason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Task 11.2's {@code transfer_created_total{code}} / {@code transfer_rejected_total{reason}} for
 * the two transfer codes this service posts — {@code CONTRIBUTION_ALLOTMENT} (allotment) and {@code
 * VESTING} (vesting). {@code code}/{@code reason} are both small, fixed enums ({@link
 * LedgerCodes.TransferCode}, {@link LedgerRejectionReason}), so a tagged counter looked up per call
 * cannot grow unbounded.
 */
@Component
public class AllotmentMetrics {

  private final MeterRegistry registry;

  public AllotmentMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** {@code count} transfers of {@code code} landed — one linked chain leg counts as one. */
  public void transfersCreated(LedgerCodes.TransferCode code, int count) {
    registry.counter("transfer_created_total", "code", code.name()).increment(count);
  }

  public void transferRejected(LedgerRejectionReason reason) {
    registry.counter("transfer_rejected_total", "reason", reason.name()).increment();
  }
}
