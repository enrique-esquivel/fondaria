package dev.fondaria.contribution.cashreceipt.web;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.cashreceipt.CashReceiptOutcome;
import dev.fondaria.contribution.cashreceipt.CashReceiptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manual reconciliation entry of ADR-0010: an ops operator, having seen a wire on the bank
 * statement, posts it into the ledger.
 *
 * <h2>This endpoint ships unauthenticated, and that is a known gap</h2>
 *
 * <p>ADR-0010 calls it internal and says it "must be authorized separately — an operator posting
 * money into the ledger is a far stronger privilege than an employer submitting a file." That
 * authorization is Phase 9's (identity) and Phase 10's (edge). No scheme is invented here, because
 * inventing one would have to be removed. Until then the endpoint must not be exposed outside the
 * cluster, and {@code operatorId} is self-asserted — an audit trail, not an authentication.
 *
 * <h2>Status codes</h2>
 *
 * <ul>
 *   <li>{@code 201} — this call posted the movement
 *   <li>{@code 200} — the same request again; the original outcome, and nothing moved twice
 *   <li>{@code 409} — a request for this receipt is in flight; retry
 *   <li>{@code 422} — the key was reused for a different request, or the ledger refused it
 *   <li>{@code 503} — recorded, movement unconfirmed; retry converges
 * </ul>
 *
 * <p>Every binding annotation names its parameter explicitly. The build does not compile with
 * {@code -parameters}, so an unnamed {@code @PathVariable} or {@code @RequestHeader} resolves to
 * nothing at runtime and fails every request it appears on — a failure that shows up only when the
 * endpoint is called, never at compile time.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/cash-receipts")
public class CashReceiptController {

  private final CashReceiptService cashReceipts;

  public CashReceiptController(CashReceiptService cashReceipts) {
    this.cashReceipts = cashReceipts;
  }

  /**
   * {@code Idempotency-Key} is required rather than optional. It is what the {@code transfer_id} is
   * derived from, so without it there is no way for a retry to converge on the same movement — and
   * generating one here would defeat the point entirely, giving every retry a new id and a second
   * credit.
   */
  @PostMapping
  public ResponseEntity<CashReceiptResponse> record(
      @PathVariable("organizationId") String organizationId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestBody RecordCashReceiptRequest request) {
    String key = requireIdempotencyKey(idempotencyKey);
    OrganizationId orgId = parseId(organizationId);
    CashReceiptOutcome outcome = cashReceipts.record(request.toCommand(orgId, key));
    HttpStatus status =
        outcome.disposition() == CashReceiptOutcome.Disposition.POSTED
            ? HttpStatus.CREATED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(CashReceiptResponse.of(outcome.receipt()));
  }

  /**
   * Bounded, because it becomes a database key. Not validated as a UUID, though
   * docs/DESIGN-DECISIONS.md recommends callers generate one: rejecting a caller's well-formed key
   * for not being a UUID would break retries for no gain, since the key's only job is to be the
   * same string twice.
   */
  private static String requireIdempotencyKey(String raw) {
    String key = raw == null ? "" : raw.trim();
    if (key.isEmpty()) {
      throw new InvalidCashReceiptRequestException("Idempotency-Key must not be blank");
    }
    if (key.length() > 200) {
      throw new InvalidCashReceiptRequestException(
          "Idempotency-Key must be at most 200 characters");
    }
    return key;
  }

  private static OrganizationId parseId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidCashReceiptRequestException("organizationId is not a valid UUID");
    }
  }
}
