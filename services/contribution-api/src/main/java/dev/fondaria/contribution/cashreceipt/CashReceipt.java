package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of {@code workflow.cash_receipt}: an operator's assertion about a wire, and where its
 * ledger movement stands.
 *
 * <p><b>This is not a balance and must never be summed.</b> What an employer holds is read from
 * {@code EMPLOYER_COLLECTIONS} through {@code LedgerPort} (ADR-0008 invariant 1, restated by
 * ADR-0011). {@link #amount()} is what one wire was asserted to be — nothing more.
 *
 * @param transferId derived from {@code (organizationId, idempotencyKey)}, so it is the same value
 *     on every retry and is what ties this row to its ledger movement
 * @param rejectionReason present exactly when {@link #status()} is {@link
 *     CashReceiptStatus#REJECTED} — the database enforces the correspondence, so a posted receipt
 *     can never carry a stale explanation of a failure that was later repaired
 */
public record CashReceipt(
    OrganizationId organizationId,
    String idempotencyKey,
    TransferId transferId,
    Money amount,
    LocalDate valueDate,
    String bankReference,
    String operatorId,
    String requestFingerprint,
    CashReceiptStatus status,
    Optional<String> rejectionReason,
    Instant createdAt,
    Instant updatedAt) {

  public CashReceipt {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(bankReference, "bankReference must not be null");
    Objects.requireNonNull(operatorId, "operatorId must not be null");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(rejectionReason, "rejectionReason must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (rejectionReason.isPresent() != (status == CashReceiptStatus.REJECTED)) {
      throw new IllegalArgumentException(
          "rejectionReason is present exactly when status is REJECTED");
    }
  }

  /** Whether this receipt's movement is settled, either way — nothing further will change it. */
  public boolean isResolved() {
    return status != CashReceiptStatus.UNCONFIRMED;
  }

  /** As {@link RecordCashReceiptCommand#toString()}: no idempotency key, no amount. */
  @Override
  public String toString() {
    return "CashReceipt[organizationId="
        + organizationId
        + ", status="
        + status
        + ", valueDate="
        + valueDate
        + ", idempotencyKey=REDACTED, amount=REDACTED]";
  }
}
