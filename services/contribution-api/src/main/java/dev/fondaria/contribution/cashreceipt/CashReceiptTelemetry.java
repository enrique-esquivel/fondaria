package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TransferId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Task 11.2's domain metrics and trace correlation for the one movement this service posts — {@code
 * CASH_RECEIPT}. One collaborator rather than two, since every hook point in {@link
 * CashReceiptService} needs both a metric recorded and (for a new post) the trace linked.
 *
 * <p>{@code code} and {@code reason} are both small, fixed enums ({@link LedgerCodes.TransferCode},
 * {@link LedgerRejectionReason}) — looking a tagged counter up per call, rather than
 * pre-registering one per value, cannot grow unbounded (the cardinality-bounded acceptance
 * criterion is about an open-ended label such as an account id, not a closed enum).
 */
@Component
public class CashReceiptTelemetry {

  private final TraceCorrelationPublisher correlation;
  private final MeterRegistry registry;
  private final Counter transferCreated;
  private final Counter idempotencyReplay;

  public CashReceiptTelemetry(TraceCorrelationPublisher correlation, MeterRegistry registry) {
    this.correlation = correlation;
    this.registry = registry;
    this.transferCreated =
        Counter.builder("transfer_created_total")
            .tag("code", LedgerCodes.TransferCode.CASH_RECEIPT.name())
            .description("Transfers created by contribution-api, by ledger transfer code")
            .register(registry);
    this.idempotencyReplay =
        Counter.builder("idempotency_replay_total")
            .description("Requests answered from a prior resolution, without touching the ledger")
            .register(registry);
  }

  /**
   * Writes the {@code transferId -> traceparent} correlation entry, best-effort. Called right
   * before the ledger post so the entry exists no later than the CDC event it is meant to link.
   */
  public void correlateTrace(TransferId transferId) {
    correlation.publish(transferId);
  }

  public void transferCreated() {
    transferCreated.increment();
  }

  public void transferRejected(LedgerRejectionReason reason) {
    registry.counter("transfer_rejected_total", "reason", reason.name()).increment();
  }

  /** A request answered from a cached, claimed-resolved, or ledger-{@code AlreadyExists} row. */
  public void idempotencyReplay() {
    idempotencyReplay.increment();
  }

  /** The same idempotency key used for a materially different or a concurrently-claimed request. */
  public void idempotencyConflict(String reason) {
    registry.counter("idempotency_conflict_total", "reason", reason).increment();
  }
}
