package dev.fondaria.bridge.transform;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.publish.LedgerTopics;

/**
 * One of the two messages the bridge produces to {@code ledger.entries.v1} for one change event —
 * the debit leg and the credit leg — each keyed by {@link #accountId()}.
 *
 * <p>Exploding the transfer into one message per leg is what lets the entries topic be partitioned
 * by account: every event touching an account lands in the same partition, in commit order
 * (phase-04-cdc.md Task 4.3). Each entry carries that one account's post-event balance counters as
 * the ledger reported them.
 */
public record EntryEvent(
    String schemaVersion,
    String eventType,
    long ledger,
    String timestamp,
    String transferId,
    int transferCode,
    String transferTimestamp,
    String amount,
    EntryDirection direction,
    String accountId,
    int accountCode,
    int accountFlags,
    String debitsPending,
    String debitsPosted,
    String creditsPending,
    String creditsPosted,
    String accountUserData128,
    String accountUserData64,
    long accountUserData32,
    String pendingId) {

  public static EntryEvent forLeg(CdcMessage message, EntryDirection direction) {
    CdcMessage.Transfer t = message.transfer();
    CdcMessage.Account account =
        direction == EntryDirection.DEBIT ? message.debitAccount() : message.creditAccount();
    return new EntryEvent(
        LedgerTopics.SCHEMA_VERSION,
        message.type().wireName(),
        message.ledger(),
        message.timestamp(),
        t.id(),
        t.code(),
        t.timestamp(),
        t.amount(),
        direction,
        account.id(),
        account.code(),
        account.flags(),
        account.debitsPending(),
        account.debitsPosted(),
        account.creditsPending(),
        account.creditsPosted(),
        account.userData128(),
        account.userData64(),
        account.userData32(),
        t.pendingId());
  }
}
