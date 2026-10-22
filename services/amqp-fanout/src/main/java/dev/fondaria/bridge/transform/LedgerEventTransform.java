package dev.fondaria.bridge.transform;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.publish.LedgerTopics;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fanout service's whole business logic: one change event becomes exactly three AMQP messages —
 * one transfer message published to ledger.transfers.v1, and two entry messages published to
 * ledger.entries.v1 keyed by account id, one per leg (ADR-0022).
 *
 * <p>Pure and total: same input, same output, no I/O, never throws for a well-formed {@link
 * CdcMessage}. {@link dev.fondaria.bridge.cdc.CdcMessageParser} has already rejected anything that
 * would make this ambiguous.
 */
@Component
public class LedgerEventTransform {

  /** The three records for one change event, transfer first then the debit and credit legs. */
  public List<LedgerRecord> explode(CdcMessage message) {
    return List.of(
        new LedgerRecord(
            LedgerTopics.TRANSFERS, message.transfer().id(), TransferEvent.from(message)),
        new LedgerRecord(
            LedgerTopics.ENTRIES,
            message.debitAccount().id(),
            EntryEvent.forLeg(message, EntryDirection.DEBIT)),
        new LedgerRecord(
            LedgerTopics.ENTRIES,
            message.creditAccount().id(),
            EntryEvent.forLeg(message, EntryDirection.CREDIT)));
  }
}
