package dev.fondaria.contribution.cashreceipt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.cashreceipt.RecordCashReceiptCommand;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where a malformed cash-receipt body is turned away, and what the caller is told when it is. */
class RecordCashReceiptRequestTest {

  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());

  @Test
  @DisplayName("a well-formed body becomes a command carrying integer cents")
  void wellFormedBody() {
    RecordCashReceiptCommand command =
        new RecordCashReceiptRequest(
                250_000L, "MXN", "2026-08-18", "SPEI-2026-08-18-0001", "ops-marta")
            .toCommand(ORGANIZATION, "key-1");

    assertThat(command.amount().cents()).isEqualTo(250_000L);
    assertThat(command.amount().currency()).isEqualTo(CurrencyCode.MXN);
    assertThat(command.valueDate()).isEqualTo(LocalDate.of(2026, 8, 18));
    assertThat(command.idempotencyKey()).isEqualTo("key-1");
    assertThat(command.operatorId()).isEqualTo("ops-marta");
  }

  @Test
  @DisplayName("an absent amount is distinguished from a zero one")
  void absentAmount() {
    assertThatThrownBy(() -> request(null).toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessage("amountMinorUnits must be supplied");
  }

  @Test
  @DisplayName("a zero or negative amount is refused, and the message says why a negative is wrong")
  void nonPositiveAmount() {
    assertThatThrownBy(() -> request(0L).toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> request(-1L).toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessageContaining("never a negative receipt");
  }

  @Test
  @DisplayName("the currency must be present and must be MXN — v1 is Mexico-only")
  void currencyMustBeMxn() {
    assertThatThrownBy(
            () ->
                new RecordCashReceiptRequest(250_000L, null, "2026-08-18", "SPEI-1", "ops-marta")
                    .toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class);
    assertThatThrownBy(
            () ->
                new RecordCashReceiptRequest(250_000L, "USD", "2026-08-18", "SPEI-1", "ops-marta")
                    .toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessage("currency must be MXN");
  }

  @Test
  @DisplayName("the value date must be a date, and it may be in the past")
  void valueDate() {
    assertThatThrownBy(
            () ->
                new RecordCashReceiptRequest(250_000L, "MXN", "18/08/2026", "SPEI-1", "ops-marta")
                    .toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessageContaining("YYYY-MM-DD");
    assertThat(
            new RecordCashReceiptRequest(250_000L, "MXN", "2020-01-02", "SPEI-1", "ops-marta")
                .toCommand(ORGANIZATION, "key-1")
                .valueDate())
        .as("a wire seen today may have been credited days ago")
        .isEqualTo(LocalDate.of(2020, 1, 2));
  }

  @Test
  @DisplayName("the bank reference and the operator are both required")
  void bankReferenceAndOperatorRequired() {
    assertThatThrownBy(
            () ->
                new RecordCashReceiptRequest(250_000L, "MXN", "2026-08-18", "  ", "ops-marta")
                    .toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessage("bankReference must not be blank");
    assertThatThrownBy(
            () ->
                new RecordCashReceiptRequest(250_000L, "MXN", "2026-08-18", "SPEI-1", null)
                    .toCommand(ORGANIZATION, "key-1"))
        .isInstanceOf(InvalidCashReceiptRequestException.class)
        .hasMessage("operatorId must not be blank");
  }

  @Test
  @DisplayName("neither the amount nor the bank reference can be printed by accident")
  void toStringRedacts() {
    String rendered =
        new RecordCashReceiptRequest(
                250_000L, "MXN", "2026-08-18", "SPEI-2026-08-18-0001", "ops-marta")
            .toString();

    assertThat(rendered).doesNotContain("250000").doesNotContain("SPEI-2026-08-18-0001");
  }

  private static RecordCashReceiptRequest request(Long cents) {
    return new RecordCashReceiptRequest(
        cents, "MXN", "2026-08-18", "SPEI-2026-08-18-0001", "ops-marta");
  }
}
