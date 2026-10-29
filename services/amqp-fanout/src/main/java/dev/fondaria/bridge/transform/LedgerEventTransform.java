package dev.fondaria.bridge.transform;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.publish.LedgerTopics;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fanout service's whole business logic: one change event becomes one transfer message
 * published to ledger.transfers.v1, plus one entry message on ledger.entries.v1 keyed by account
 * id for each leg that is a participant holding (ADR-0022, ADR-0040).
 *
 * <p>Only participant-holding legs are published as entries, because they are the only legs any
 * consumer projects ({@code ParticipantUnitsProjection}). A pooled account such as a fund's {@code
 * UNITS_IN_ISSUE} sits on one leg of every allotment, so publishing it put one entry per
 * participant leg on the single queue its id hashes to (ADR-0040). The transfer message still names
 * both accounts.
 *
 * <p>Pure and total: same input, same output, no I/O, never throws for a well-formed {@link
 * CdcMessage}. {@link dev.fondaria.bridge.cdc.CdcMessageParser} has already rejected anything that
 * would make this ambiguous.
 */
@Component
public class LedgerEventTransform {

  /** The single MXN cash ledger; every other ledger is a fund's unit ledger. */
  static final long CASH_LEDGER_ID = 484L;

  /**
   * {@code PARTICIPANT_HOLDINGS_VESTED} and {@code _UNVESTED}: the same numbers as {@code
   * dev.fondaria.ledger.LedgerCodes.AccountCode} and docs/chart-of-accounts.md §3. Declared here
   * because the fan-out does not depend on ledger-client.
   */
  static final int PARTICIPANT_HOLDINGS_VESTED = 102;

  static final int PARTICIPANT_HOLDINGS_UNVESTED = 103;

  /** The transfer record first, then the debit leg's and the credit leg's entries, if published. */
  public List<LedgerRecord> explode(CdcMessage message) {
    List<LedgerRecord> records = new ArrayList<>(3);
    records.add(
        new LedgerRecord(
            LedgerTopics.TRANSFERS, message.transfer().id(), TransferEvent.from(message)));
    if (isParticipantHolding(message.ledger(), message.debitAccount())) {
      records.add(
          new LedgerRecord(
              LedgerTopics.ENTRIES,
              message.debitAccount().id(),
              EntryEvent.forLeg(message, EntryDirection.DEBIT)));
    }
    if (isParticipantHolding(message.ledger(), message.creditAccount())) {
      records.add(
          new LedgerRecord(
              LedgerTopics.ENTRIES,
              message.creditAccount().id(),
              EntryEvent.forLeg(message, EntryDirection.CREDIT)));
    }
    return List.copyOf(records);
  }

  private static boolean isParticipantHolding(long ledger, CdcMessage.Account account) {
    return ledger != CASH_LEDGER_ID
        && (account.code() == PARTICIPANT_HOLDINGS_VESTED
            || account.code() == PARTICIPANT_HOLDINGS_UNVESTED);
  }
}
