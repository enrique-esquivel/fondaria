package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An operator's assertion that a wire landed: "this employer sent this much, on this date, and here
 * is the statement line it appears on" (ADR-0010).
 *
 * <p>It is an <b>assertion</b>, not an instruction, and that distinction is the whole reason
 * ADR-0011 lets this one handler write two stores. "Somebody claims a wire exists" is a different
 * fact from "money moved", so the claim is written first and the movement second.
 *
 * @param organizationId whose money this is. The receipt is refused if no organization is
 *     registered under it, so a well-formed id belonging to nobody can never open an account.
 * @param idempotencyKey the caller's key, scoped to {@code organizationId}. The derived {@code
 *     transfer_id} comes from it, which is what makes the ledger — not this service, and certainly
 *     not Redis — the idempotency authority.
 * @param amount MXN cents, always positive. A wire that did not arrive is not a negative receipt;
 *     an over-credit is corrected with a {@code CORRECTION} transfer (chart-of-accounts.md §4).
 * @param valueDate the date the bank credited the funds, which is not necessarily today
 * @param bankReference the statement line this receipt claims to be. Stored in PostgreSQL; the
 *     ledger carries only its derived {@code ExternalReference} (ADR-0011).
 * @param operatorId provenance of the assertion — who or what claims to have seen this wire.
 *     Self-asserted in v1: Phase 9 replaces it with the authenticated subject, and O-36's bank feed
 *     replaces the operator with a statement matcher.
 */
public record RecordCashReceiptCommand(
    OrganizationId organizationId,
    String idempotencyKey,
    Money amount,
    LocalDate valueDate,
    String bankReference,
    String operatorId) {

  public RecordCashReceiptCommand {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    bankReference = requireText(bankReference, "bankReference");
    operatorId = requireText(operatorId, "operatorId");
    if (amount.currency() != CurrencyCode.MXN) {
      throw new IllegalArgumentException("amount must be MXN");
    }
    if (amount.cents() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  /**
   * Deliberately renders neither the idempotency key nor the amount: keys are on the never-log list
   * and an amount is a monetary value (docs/ENGINEERING-RULES.md §7), and a record's generated
   * {@code toString} is exactly how both reach a log by accident.
   */
  @Override
  public String toString() {
    return "RecordCashReceiptCommand[organizationId="
        + organizationId
        + ", valueDate="
        + valueDate
        + ", idempotencyKey=REDACTED, amount=REDACTED]";
  }
}
