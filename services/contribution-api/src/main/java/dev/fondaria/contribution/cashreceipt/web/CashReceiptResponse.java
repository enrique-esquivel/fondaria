package dev.fondaria.contribution.cashreceipt.web;

import dev.fondaria.contribution.cashreceipt.CashReceipt;
import dev.fondaria.ledger.TransferIdDeriver;
import java.time.Instant;

/**
 * A cash receipt as returned by the API.
 *
 * <p><b>There is no balance field, and adding one would be a defect.</b> What an employer holds in
 * {@code EMPLOYER_COLLECTIONS} is read from the ledger at the moment it is asked for; putting it
 * here would mean a replayed receipt — served from cache, up to 24 hours old — reporting a balance
 * as it stood when the wire was first keyed. A stale balance beside a correct one is the
 * self-inflicted wound 01-DECISIONS-TO-DISCUSS.md C4 describes.
 *
 * <p>{@code transferId} is the tie-out handle: derived, so it is identical on every retry, and it
 * is what the ledger, the logs and {@code workflow.cash_receipt} all agree on. The idempotency key
 * that produced it is deliberately not echoed — it is on the never-log list, and a response body
 * ends up in logs (docs/ENGINEERING-RULES.md §7).
 *
 * <p>{@code amountMinorUnits} is a JSON integer of cents on the way out for the same reason it is
 * one on the way in.
 */
public record CashReceiptResponse(
    String organizationId,
    String transferId,
    long amountMinorUnits,
    String currency,
    String valueDate,
    String bankReference,
    String operatorId,
    String status,
    Instant recordedAt,
    Instant statusUpdatedAt) {

  public static CashReceiptResponse of(CashReceipt receipt) {
    return new CashReceiptResponse(
        receipt.organizationId().toString(),
        TransferIdDeriver.toUuid(receipt.transferId()).toString(),
        receipt.amount().cents(),
        receipt.amount().currency().name(),
        receipt.valueDate().toString(),
        receipt.bankReference(),
        receipt.operatorId(),
        receipt.status().name(),
        receipt.createdAt(),
        receipt.updatedAt());
  }
}
