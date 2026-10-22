package dev.fondaria.bridge.transform;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.publish.LedgerTopics;

/**
 * The single message the bridge produces to {@code ledger.transfers.v1} for one change event, keyed
 * by {@link #transferId()}.
 *
 * <p>It carries the transfer and the identity of both legs, but not either account's balance
 * counters — those belong on the per-account {@link EntryEvent}s, where per-account ordering holds.
 * Amounts are integral decimal strings, unchanged from the ledger; decoding them to {@code
 * Money}/{@code Units} by ledger id is Task 4.4's job.
 */
public record TransferEvent(
    String schemaVersion,
    String eventType,
    long ledger,
    String timestamp,
    String transferId,
    String amount,
    String pendingId,
    int transferCode,
    int transferFlags,
    String transferTimestamp,
    String userData128,
    String userData64,
    long userData32,
    String debitAccountId,
    String creditAccountId,
    int debitAccountCode,
    int creditAccountCode) {

  public static TransferEvent from(CdcMessage message) {
    CdcMessage.Transfer t = message.transfer();
    return new TransferEvent(
        LedgerTopics.SCHEMA_VERSION,
        message.type().wireName(),
        message.ledger(),
        message.timestamp(),
        t.id(),
        t.amount(),
        t.pendingId(),
        t.code(),
        t.flags(),
        t.timestamp(),
        t.userData128(),
        t.userData64(),
        t.userData32(),
        message.debitAccount().id(),
        message.creditAccount().id(),
        message.debitAccount().code(),
        message.creditAccount().code());
  }
}
