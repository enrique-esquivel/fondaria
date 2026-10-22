package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is what stands between "the same request twice" and "one key, two different
 * wires", and it is stored for as long as the receipt is. So both halves are pinned here: the exact
 * canonical bytes, and the fact that every field of the request reaches them.
 */
class CashReceiptFingerprintTest {

  private static final OrganizationId ORGANIZATION =
      new OrganizationId(UUID.fromString("4d1a7f60-6a2f-4b6c-9d3e-1f5a2b8c7d90"));
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);

  @Test
  @DisplayName("the canonical form is exactly this, and changing it is a decision not an accident")
  void canonicalFormIsPinned() {
    assertThat(CashReceiptFingerprint.canonicalForm(command(250_000L)))
        .isEqualTo(
            """
            v1
            organizationId=4d1a7f60-6a2f-4b6c-9d3e-1f5a2b8c7d90
            amountMinorUnits=250000
            currency=MXN
            valueDate=2026-08-18
            bankReference=SPEI-2026-08-18-0001
            operatorId=ops-marta"""
                .stripTrailing());
  }

  @Test
  @DisplayName("the amount appears as integer cents, never as a decimal")
  void amountIsIntegerCents() {
    assertThat(CashReceiptFingerprint.canonicalForm(command(250_000L)))
        .contains("amountMinorUnits=250000")
        .doesNotContain("2500.00");
  }

  @Test
  @DisplayName("the same request fingerprints identically, every time")
  void isStable() {
    assertThat(CashReceiptFingerprint.of(command(250_000L)))
        .isEqualTo(CashReceiptFingerprint.of(command(250_000L)));
  }

  @Test
  @DisplayName("a one-cent difference is a different request")
  void oneCentChangesTheFingerprint() {
    assertThat(CashReceiptFingerprint.of(command(250_000L)))
        .isNotEqualTo(CashReceiptFingerprint.of(command(250_001L)));
  }

  @Test
  @DisplayName("every field of the request reaches the fingerprint")
  void everyFieldMatters() {
    String baseline = CashReceiptFingerprint.of(command(250_000L));

    assertThat(
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    new OrganizationId(UUID.randomUUID()),
                    "key-1",
                    Money.of(250_000L, CurrencyCode.MXN),
                    VALUE_DATE,
                    "SPEI-2026-08-18-0001",
                    "ops-marta")))
        .as("a different organization")
        .isNotEqualTo(baseline);
    assertThat(
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    ORGANIZATION,
                    "key-1",
                    Money.of(250_000L, CurrencyCode.MXN),
                    VALUE_DATE.minusDays(1),
                    "SPEI-2026-08-18-0001",
                    "ops-marta")))
        .as("a different value date")
        .isNotEqualTo(baseline);
    assertThat(
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    ORGANIZATION,
                    "key-1",
                    Money.of(250_000L, CurrencyCode.MXN),
                    VALUE_DATE,
                    "SPEI-2026-08-18-0002",
                    "ops-marta")))
        .as("a different bank reference")
        .isNotEqualTo(baseline);
    assertThat(
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    ORGANIZATION,
                    "key-1",
                    Money.of(250_000L, CurrencyCode.MXN),
                    VALUE_DATE,
                    "SPEI-2026-08-18-0001",
                    "ops-hugo")))
        .as("a different operator — these are the bytes a signature would one day cover")
        .isNotEqualTo(baseline);
  }

  @Test
  @DisplayName("the idempotency key is not part of what is compared")
  void theKeyIdentifiesTheRowRatherThanFormingPartOfIt() {
    assertThat(
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    ORGANIZATION,
                    "a-completely-different-key",
                    Money.of(250_000L, CurrencyCode.MXN),
                    VALUE_DATE,
                    "SPEI-2026-08-18-0001",
                    "ops-marta")))
        .isEqualTo(CashReceiptFingerprint.of(command(250_000L)));
  }

  @Test
  @DisplayName("the digest is lowercase hex SHA-256")
  void digestShape() {
    assertThat(CashReceiptFingerprint.of(command(250_000L))).matches("^[0-9a-f]{64}$");
  }

  private static RecordCashReceiptCommand command(long cents) {
    return new RecordCashReceiptCommand(
        ORGANIZATION,
        "key-1",
        Money.of(cents, CurrencyCode.MXN),
        VALUE_DATE,
        "SPEI-2026-08-18-0001",
        "ops-marta");
  }
}
