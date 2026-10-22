package dev.fondaria.bridge.transform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.bridge.cdc.CdcMessage;
import dev.fondaria.bridge.cdc.ChangeEventType;
import dev.fondaria.bridge.publish.LedgerTopics;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerEventTransformTest {

  private final LedgerEventTransform transform = new LedgerEventTransform();

  private static CdcMessage sampleTransfer() {
    return new CdcMessage(
        "1700000000000000000",
        ChangeEventType.SINGLE_PHASE,
        484,
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
            3,
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
            4,
            0,
            "1699999999999999998"));
  }

  @Test
  void oneChangeEventBecomesExactlyThreeRecords() {
    List<LedgerRecord> records = transform.explode(sampleTransfer());

    assertThat(records).hasSize(3);
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
                assertThat(t.ledger()).isEqualTo(484);
                assertThat(t.transferId()).isEqualTo("900000000000000001");
              } else {
                EntryEvent e = (EntryEvent) payload;
                assertThat(e.ledger()).isEqualTo(484);
                assertThat(e.transferId()).isEqualTo("900000000000000001");
              }
            });
  }
}
