package dev.fondaria.projection.consume;

import dev.fondaria.ledger.LedgerEntryEvent;
import dev.fondaria.ledger.LedgerTransferEvent;
import java.util.List;

/**
 * Where a decoded CDC event goes once {@link LedgerEventListener} has parsed and routed it.
 *
 * <p>Phase 5's consumer skeleton (Task 5.3) shipped a no-op sink; {@link JdbcProjectionSink} (Task
 * 5.4) is the real one — the transfer projection and the checkpoint, in one Postgres transaction.
 * Task 5.5 adds the sparse unit position ({@code participant_units}) to the same transaction, on
 * the entry-event path.
 *
 * <p>An implementation MUST be idempotent on {@code transferId} (Task 5.4): every event is assumed
 * to arrive more than once. A transient failure (a database blip) is signalled by throwing any
 * unchecked exception — the listener does not acknowledge, and the record is retried with bounded
 * backoff. A sink must never throw {@code dev.fondaria.ledger.UnknownLedgerEventVersionException}
 * or {@code MalformedLedgerEventException}: those are the decoder's to raise and mean "halt", not
 * "retry".
 */
public interface ProjectionSink {

  /**
   * Project one transfer event. See {@link ProjectionSink} for the idempotency and failure
   * contract.
   *
   * @param consumer the AMQP consumer that received the event: its checkpoint row is the one this
   *     transaction advances (ADR-0032 §1)
   */
  void accept(LedgerTransferEvent event, ProjectionCheckpoint.ConsumerRef consumer);

  /** Project one entry event (one leg of a transfer). Same contract. */
  void accept(LedgerEntryEvent event, ProjectionCheckpoint.ConsumerRef consumer);

  /**
   * Project a batch of decoded deliveries, in delivery order as received (ADR-0034 §2). Same
   * idempotency and failure contract as the per-event methods, for the whole batch: it either
   * completes, or throws and the listener retries the whole batch.
   *
   * <p>This default projects each event through the per-event methods, one transaction each —
   * correct, but without the single commit the batch exists for. {@link JdbcProjectionSink}
   * overrides it with one transaction; a test sink inherits this and keeps its per-event behaviour.
   */
  default void acceptBatch(List<Delivery> deliveries) {
    for (Delivery delivery : deliveries) {
      switch (delivery) {
        case Delivery.Transfer transfer -> accept(transfer.event(), transfer.consumer());
        case Delivery.Entry entry -> accept(entry.event(), entry.consumer());
      }
    }
  }

  /** One decoded delivery and the consumer that received it (ADR-0032 §1's checkpoint row). */
  sealed interface Delivery permits Delivery.Transfer, Delivery.Entry {

    ProjectionCheckpoint.ConsumerRef consumer();

    /** The ledger timestamp the event carries, as the decimal string the CDC writes. */
    String ledgerTimestamp();

    record Transfer(LedgerTransferEvent event, ProjectionCheckpoint.ConsumerRef consumer)
        implements Delivery {
      @Override
      public String ledgerTimestamp() {
        return event.timestamp();
      }
    }

    record Entry(LedgerEntryEvent event, ProjectionCheckpoint.ConsumerRef consumer)
        implements Delivery {
      @Override
      public String ledgerTimestamp() {
        return event.timestamp();
      }
    }
  }
}
