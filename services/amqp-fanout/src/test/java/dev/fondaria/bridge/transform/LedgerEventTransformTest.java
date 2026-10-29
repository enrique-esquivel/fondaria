package dev.fondaria.bridge.transform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.cdc.ChangeEventType;
import dev.fondaria.bridge.publish.LedgerTopics;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerEventTransformTest {

  private final LedgerEventTransform transform = new LedgerEventTransform();

  private static final long UNIT_LEDGER = 1001;
  private static final int UNITS_IN_ISSUE = 101;
  private static final int VESTED = 102;
  private static final int UNVESTED = 103;
  private static final int UNIT_ROUNDING = 104;

  /** A VESTING transfer: both legs are participant holdings. */
  private static CdcMessage sampleTransfer() {
    return sample(UNIT_LEDGER, UNVESTED, VESTED);
  }

  private static CdcMessage sample(long ledger, int debitCode, int creditCode) {
    return new CdcMessage(
        "1700000000000000000",
        ChangeEventType.SINGLE_PHASE,
        ledger,
        new CdcMessage.Transfer(
            "900000000000000001", "125000", "0", "0", "0", 0, 0, 7, 0, "1700000000000000001"),
        new CdcMessage.Account(
            "700000000000000010",
            "0",
            "125000",
            "0",
            "0",
            "0",
            "0",
            0,
            debitCode,
            0,
            "1699999999999999999"),
        new CdcMessage.Account(
            "700000000000000020",
            "0",
            "0",
            "0",
            "125000",
            "0",
            "0",
            0,
            creditCode,
            0,
            "1699999999999999998"));
  }

  @Test
  void aTransferBetweenTwoHoldingsBecomesThreeRecords() {
    List<LedgerRecord> records = transform.explode(sampleTransfer());

    assertThat(records).hasSize(3);
  }

  @Test
  void anAllotmentLegPublishesOnlyTheHoldingEntryNotUnitsInIssue() {
    List<LedgerRecord> records = transform.explode(sample(UNIT_LEDGER, UNITS_IN_ISSUE, VESTED));

    assertThat(records)
        .extracting(LedgerRecord::topic)
        .containsExactly(LedgerTopics.TRANSFERS, LedgerTopics.ENTRIES);
    assertThat(records.get(1).key()).isEqualTo("700000000000000020");
    assertThat(((EntryEvent) records.get(1).payload()).direction())
        .isEqualTo(EntryDirection.CREDIT);
  }

  @Test
  void aRedemptionLegPublishesOnlyTheHoldingEntry() {
    List<LedgerRecord> records = transform.explode(sample(UNIT_LEDGER, VESTED, UNITS_IN_ISSUE));

    assertThat(records).hasSize(2);
    assertThat(records.get(1).key()).isEqualTo("700000000000000010");
    assertThat(((EntryEvent) records.get(1).payload()).direction()).isEqualTo(EntryDirection.DEBIT);
  }

  @Test
  void pooledUnitLegsPublishOnlyTheTransfer() {
    List<LedgerRecord> records =
        transform.explode(sample(UNIT_LEDGER, UNITS_IN_ISSUE, UNIT_ROUNDING));

    assertThat(records)
        .singleElement()
        .extracting(LedgerRecord::topic)
        .isEqualTo(LedgerTopics.TRANSFERS);
  }

  @Test
  void aCashEventPublishesOnlyTheTransferEvenWhenItsCodesMatchHoldingNumbers() {
    // Account codes are read per ledger: 102 on the cash ledger is not a participant holding.
    assertThat(transform.explode(sample(484, 3, 5))).hasSize(1);
    assertThat(transform.explode(sample(484, VESTED, UNVESTED))).hasSize(1);
  }

  @Test
  void theTransferRecordIsKeyedByTransferId() {
    LedgerRecord transferRecord = transform.explode(sampleTransfer()).getFirst();

    assertThat(transferRecord.topic()).isEqualTo(LedgerTopics.TRANSFERS);
    assertThat(transferRecord.key()).isEqualTo("900000000000000001");
    assertThat(transferRecord.payload()).isInstanceOf(TransferEvent.class);
    TransferEvent event = (TransferEvent) transferRecord.payload();
    assertThat(event.amount()).isEqualTo("125000");
    assertThat(event.transferCode()).isEqualTo(7);
    assertThat(event.debitAccountId()).isEqualTo("700000000000000010");
    assertThat(event.creditAccountId()).isEqualTo("700000000000000020");
    assertThat(event.schemaVersion()).isEqualTo(LedgerTopics.SCHEMA_VERSION);
  }

  @Test
  void theTwoEntryRecordsAreKeyedByTheirOwnAccountAndCarryTheirDirection() {
    List<LedgerRecord> records = transform.explode(sampleTransfer());

    LedgerRecord debit = records.get(1);
    LedgerRecord credit = records.get(2);

    assertThat(debit.topic()).isEqualTo(LedgerTopics.ENTRIES);
    assertThat(debit.key()).isEqualTo("700000000000000010");
    assertThat(((EntryEvent) debit.payload()).direction()).isEqualTo(EntryDirection.DEBIT);
    assertThat(((EntryEvent) debit.payload()).amount()).isEqualTo("125000");
    assertThat(((EntryEvent) debit.payload()).debitsPosted()).isEqualTo("125000");

    assertThat(credit.topic()).isEqualTo(LedgerTopics.ENTRIES);
    assertThat(credit.key()).isEqualTo("700000000000000020");
    assertThat(((EntryEvent) credit.payload()).direction()).isEqualTo(EntryDirection.CREDIT);
    assertThat(((EntryEvent) credit.payload()).creditsPosted()).isEqualTo("125000");
  }

  @Test
  void everyRecordCarriesTheSameTransferAndLedger() {
    List<LedgerRecord> records = transform.explode(sampleTransfer());

    assertThat(records)
        .extracting(LedgerRecord::payload)
        .allSatisfy(
            payload -> {
              if (payload instanceof TransferEvent t) {
                assertThat(t.ledger()).isEqualTo(UNIT_LEDGER);
                assertThat(t.transferId()).isEqualTo("900000000000000001");
              } else {
                EntryEvent e = (EntryEvent) payload;
                assertThat(e.ledger()).isEqualTo(UNIT_LEDGER);
                assertThat(e.transferId()).isEqualTo("900000000000000001");
              }
            });
  }
}
