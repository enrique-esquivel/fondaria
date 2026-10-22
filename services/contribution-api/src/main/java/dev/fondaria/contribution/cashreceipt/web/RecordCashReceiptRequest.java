package dev.fondaria.contribution.cashreceipt.web;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.cashreceipt.RecordCashReceiptCommand;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The body an operator submits, having found a wire on the bank statement (ADR-0010).
 *
 * <p><b>{@code amountMinorUnits} is a JSON integer of MXN cents, and nothing else will do.</b> Not
 * a decimal, not a string, not a float — bound to a {@code Long} so a fractional value is a 400
 * rather than something that silently rounds. A {@code double} here would be a defect of the exact
 * kind 01-DECISIONS-TO-DISCUSS.md C1 exists to prevent, in the first request handler in the
 * platform that moves real money.
 *
 * <p>The {@code Idempotency-Key} is not a field: it is a header, because it describes the request
 * rather than the wire.
 *
 * @param amountMinorUnits boxed rather than primitive so an absent field is distinguishable from a
 *     submitted zero — one is a malformed request, the other an operator asserting an empty wire,
 *     and they deserve different messages
 * @param operatorId who or what claims to have seen this wire. Required, and self-asserted: there
 *     is no authenticated subject to take it from until Phase 9. See {@code ADR-0010} and {@code
 *     docs/01-DECISIONS-TO-DISCUSS.md} G16.
 */
public record RecordCashReceiptRequest(
    Long amountMinorUnits,
    String currency,
    String valueDate,
    String bankReference,
    String operatorId) {

  public RecordCashReceiptCommand toCommand(OrganizationId organizationId, String idempotencyKey) {
    if (amountMinorUnits == null) {
      throw new InvalidCashReceiptRequestException("amountMinorUnits must be supplied");
    }
    if (amountMinorUnits <= 0) {
      throw new InvalidCashReceiptRequestException(
          "amountMinorUnits must be a positive integer number of cents; a correction is a"
              + " separate entry, never a negative receipt");
    }
    if (bankReference == null || bankReference.isBlank()) {
      throw new InvalidCashReceiptRequestException("bankReference must not be blank");
    }
    if (operatorId == null || operatorId.isBlank()) {
      throw new InvalidCashReceiptRequestException("operatorId must not be blank");
    }
    return new RecordCashReceiptCommand(
        organizationId,
        idempotencyKey,
        Money.of(amountMinorUnits, parseCurrency(currency)),
        parseValueDate(valueDate),
        bankReference,
        operatorId);
  }

  /**
   * Present and {@code MXN}, rather than defaulted. v1 is MXN-only (ADR-0004), and a currency this
   * service silently assumed would be the one field nobody checked on the day a second one arrives.
   */
  private static CurrencyCode parseCurrency(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidCashReceiptRequestException("currency must be supplied, and must be MXN");
    }
    if (!CurrencyCode.MXN.name().equalsIgnoreCase(raw.trim())) {
      throw new InvalidCashReceiptRequestException("currency must be MXN");
    }
    return CurrencyCode.MXN;
  }

  /**
   * The bank's value date, which is frequently not today — a wire seen on Monday may have landed
   * Friday.
   */
  private static LocalDate parseValueDate(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidCashReceiptRequestException("valueDate must be supplied, as YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidCashReceiptRequestException("valueDate must be a date in YYYY-MM-DD form");
    }
  }

  /** Renders no amount and no bank reference; a generated {@code toString} is how both leak. */
  @Override
  public String toString() {
    return "RecordCashReceiptRequest[currency="
        + currency
        + ", valueDate="
        + valueDate
        + ", amountMinorUnits=REDACTED, bankReference=REDACTED]";
  }
}
