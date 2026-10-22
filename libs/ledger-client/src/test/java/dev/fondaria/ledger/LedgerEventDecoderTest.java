package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The decoder against the committed {@code contracts/events} examples and against every failure
 * mode Task 4.4 names: an unknown version, a fractional amount, a value that overflows the 64-bit
 * domain, an unrecognised ledger id, and structurally broken input.
 */
class LedgerEventDecoderTest {

  private final LedgerEventDecoder decoder = new LedgerEventDecoder();

  private static String fixture(String name) {
    Path root = repositoryRoot();
    try {
      return Files.readString(
          root.resolve("contracts/events").resolve(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read fixture " + name, e);
    }
  }

  // --- the happy path: the committed examples ------------------------------------------------

  @Test
  void decodesTheCommittedCashTransferExampleToMoney() {
    LedgerTransferEvent event = decoder.decodeTransfer(fixture("ledger-transfer-v1.example.json"));

    assertThat(event).isInstanceOf(LedgerTransferEvent.Cash.class);
    LedgerTransferEvent.Cash cash = (LedgerTransferEvent.Cash) event;
    assertThat(cash.schemaVersion()).isEqualTo("v1");
    assertThat(cash.eventType()).isEqualTo(ChangeEventType.SINGLE_PHASE);
    assertThat(cash.ledger()).isEqualTo(LedgerCodes.CASH_LEDGER_ID);
    assertThat(cash.transferId()).isEqualTo("900000000000000001");
    assertThat(cash.amount()).isEqualTo(Money.of(125_000L, CurrencyCode.MXN));
    assertThat(cash.pendingId()).isEmpty();
    assertThat(cash.transferCode()).isEqualTo(1);
    assertThat(cash.debitAccountId()).isEqualTo("700000000000000010");
    assertThat(cash.creditAccountId()).isEqualTo("700000000000000020");
    assertThat(cash.timestamp()).isEqualTo("1700000000000000000");
  }

  @Test
  void decodesTheCommittedEntryExampleToMoneyCounters() {
    LedgerEntryEvent event = decoder.decodeEntry(fixture("ledger-entry-v1.example.json"));

    assertThat(event).isInstanceOf(LedgerEntryEvent.Cash.class);
    LedgerEntryEvent.Cash cash = (LedgerEntryEvent.Cash) event;
    assertThat(cash.direction()).isEqualTo(EntryDirection.DEBIT);
    assertThat(cash.accountId()).isEqualTo("700000000000000010");
    assertThat(cash.amount()).isEqualTo(Money.of(125_000L, CurrencyCode.MXN));
    assertThat(cash.debitsPosted()).isEqualTo(Money.of(125_000L, CurrencyCode.MXN));
    assertThat(cash.creditsPosted()).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  // --- routing by ledger id -----------------------------------------------------------------

  @Test
  void decodesAUnitLedgerTransferToUnits() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"ledger\": 484", "\"ledger\": 1001")
            .replace("\"amount\": \"125000\"", "\"amount\": \"5000000000\"");

    LedgerTransferEvent event = decoder.decodeTransfer(json);

    assertThat(event).isInstanceOf(LedgerTransferEvent.Unit.class);
    LedgerTransferEvent.Unit unit = (LedgerTransferEvent.Unit) event;
    assertThat(unit.fundLedgerId()).isEqualTo(1001L);
    assertThat(unit.amount()).isEqualTo(Units.of(5_000_000_000L));
  }

  @Test
  void decodesAUnitLedgerEntryToUnitsCounters() {
    String json =
        fixture("ledger-entry-v1.example.json")
            .replace("\"ledger\": 484", "\"ledger\": 2048")
            .replace("\"amount\": \"125000\"", "\"amount\": \"900000000\"")
            .replace("\"debitsPosted\": \"125000\"", "\"debitsPosted\": \"900000000\"");

    LedgerEntryEvent event = decoder.decodeEntry(json);

    assertThat(event).isInstanceOf(LedgerEntryEvent.Unit.class);
    LedgerEntryEvent.Unit unit = (LedgerEntryEvent.Unit) event;
    assertThat(unit.fundLedgerId()).isEqualTo(2048L);
    assertThat(unit.amount()).isEqualTo(Units.of(900_000_000L));
    assertThat(unit.debitsPosted()).isEqualTo(Units.of(900_000_000L));
  }

  @Test
  void rejectsALedgerIdThatIsNeitherCashNorAUnitLedger() {
    String json =
        fixture("ledger-transfer-v1.example.json").replace("\"ledger\": 484", "\"ledger\": 7");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("unrecognised ledger id: 7");
  }

  // --- version handling -------------------------------------------------------------------

  @Test
  void rejectsAnUnknownSchemaVersionWithoutReadingAnyOtherField() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"schemaVersion\": \"v1\"", "\"schemaVersion\": \"v2\"");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(UnknownLedgerEventVersionException.class)
        .satisfies(
            e -> assertThat(((UnknownLedgerEventVersionException) e).version()).contains("v2"))
        .hasMessageContaining("v2")
        .hasMessageContaining("v1");
  }

  @Test
  void rejectsAMissingSchemaVersion() {
    String json =
        fixture("ledger-transfer-v1.example.json").replace("\"schemaVersion\": \"v1\",", "");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(UnknownLedgerEventVersionException.class)
        .satisfies(e -> assertThat(((UnknownLedgerEventVersionException) e).version()).isEmpty());
  }

  @Test
  void anUnknownVersionIsNotConfusedWithMalformedContent() {
    // Body is otherwise garbage, but the version check comes first and wins.
    String json = "{\"schemaVersion\":\"v9\",\"eventType\":42}";

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(UnknownLedgerEventVersionException.class);
  }

  // --- amounts decode as integers, never coerced ----------------------------------------

  @Test
  void rejectsAFractionalAmountAsAString() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"amount\": \"125000\"", "\"amount\": \"1250.5\"");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void rejectsAFractionalAmountAsAJsonNumber() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"amount\": \"125000\"", "\"amount\": 1250.5");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("fractional");
  }

  @Test
  void rejectsANegativeAmount() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"amount\": \"125000\"", "\"amount\": \"-1\"");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class);
  }

  @Test
  void rejectsAnAmountThatDoesNotFitTheSignedSixtyFourBitDomain() {
    // 2^63 exactly — one past Long.MAX_VALUE.
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"amount\": \"125000\"", "\"amount\": \"9223372036854775808\"");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("64-bit");
  }

  // --- structural failures ------------------------------------------------------------

  @Test
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> decoder.decodeTransfer("{not json"))
        .isInstanceOf(MalformedLedgerEventException.class);
  }

  @Test
  void rejectsAMissingField() {
    String json =
        fixture("ledger-transfer-v1.example.json")
            .replace("\"transferId\": \"900000000000000001\",", "");

    assertThatThrownBy(() -> decoder.decodeTransfer(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("transferId");
  }

  @Test
  void rejectsAnUnknownEventType() {
    String json =
        fixture("ledger-entry-v1.example.json").replace("\"single_phase\"", "\"triple_phase\"");

    assertThatThrownBy(() -> decoder.decodeEntry(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("triple_phase");
  }

  @Test
  void rejectsAnUnknownDirection() {
    String json = fixture("ledger-entry-v1.example.json").replace("\"DEBIT\"", "\"SIDEWAYS\"");

    assertThatThrownBy(() -> decoder.decodeEntry(json))
        .isInstanceOf(MalformedLedgerEventException.class)
        .hasMessageContaining("SIDEWAYS");
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "could not locate the repository root from " + Path.of("").toAbsolutePath());
  }
}
