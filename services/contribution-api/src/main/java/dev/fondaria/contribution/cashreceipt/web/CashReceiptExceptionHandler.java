package dev.fondaria.contribution.cashreceipt.web;

import dev.fondaria.contribution.cashreceipt.CashReceiptInFlightException;
import dev.fondaria.contribution.cashreceipt.CashReceiptRejectedException;
import dev.fondaria.contribution.cashreceipt.CashReceiptUnconfirmedException;
import dev.fondaria.contribution.cashreceipt.IdempotencyKeyReusedException;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the cash-receipt endpoint (AGENTS.md §7), following the pattern
 * {@code OrganizationExceptionHandler} sets.
 *
 * <p>Only messages this codebase authored are rendered. Anything else becomes a generic {@code 500}
 * saying nothing, because a driver, parser or JDK message can carry a SQL fragment, a hostname, an
 * amount or a bank reference. The real cause is logged, where the never-log list still applies.
 *
 * <p><b>{@code transferId} is attached to every problem that has one.</b> It is the derived, opaque
 * handle that ties a failed call to the row and to the ledger, and it is what makes a 503 or a 409
 * actionable rather than merely discouraging. The idempotency key that produced it is never
 * rendered — it is on the never-log list, and problem bodies are logged by everything they pass
 * through.
 */
@RestControllerAdvice(assignableTypes = CashReceiptController.class)
public class CashReceiptExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(CashReceiptExceptionHandler.class);

  private static final URI NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/organization-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI KEY_REUSED_TYPE =
      URI.create("https://fondaria.example/problems/idempotency-key-reused");
  private static final URI IN_FLIGHT_TYPE =
      URI.create("https://fondaria.example/problems/cash-receipt-in-flight");
  private static final URI REJECTED_TYPE =
      URI.create("https://fondaria.example/problems/cash-receipt-rejected");
  private static final URI UNCONFIRMED_TYPE =
      URI.create("https://fondaria.example/problems/cash-receipt-unconfirmed");
  private static final URI FORBIDDEN_TYPE =
      URI.create("https://fondaria.example/problems/forbidden");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  private static final URI UNAUTHORIZED_TYPE =
      URI.create("https://fondaria.example/problems/unauthorized");

  @ExceptionHandler(dev.fondaria.common.security.AuthenticationRequiredException.class)
  public ProblemDetail unauthorized(
      dev.fondaria.common.security.AuthenticationRequiredException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setType(UNAUTHORIZED_TYPE);
    problem.setTitle("Unauthorized");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(dev.fondaria.common.security.AccessDeniedProblemException.class)
  public ProblemDetail accessDenied(dev.fondaria.common.security.AccessDeniedProblemException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(FORBIDDEN_TYPE);
    problem.setTitle("Forbidden");
    problem.setDetail(e.detail());
    return problem;
  }

  @ExceptionHandler(OrganizationNotFoundException.class)
  public ProblemDetail organizationNotFound(OrganizationNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(NOT_FOUND_TYPE);
    problem.setTitle("Organization not found");
    problem.setDetail(
        "No organization is registered under id "
            + e.organizationId()
            + ", so no cash receipt can be posted for it.");
    return problem;
  }

  @ExceptionHandler(InvalidCashReceiptRequestException.class)
  public ProblemDetail invalidRequest(InvalidCashReceiptRequestException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  /**
   * A missing {@code Idempotency-Key} is answered here rather than left to Spring's default,
   * because the default renders the header name inside a framework message and says nothing about
   * why it is required.
   */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ProblemDetail missingHeader(MissingRequestHeaderException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(
        "Idempotency-Key is required: the ledger transfer id is derived from it, and without"
            + " one a retry cannot converge on the same movement.");
    return problem;
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ProblemDetail keyReused(IdempotencyKeyReusedException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(KEY_REUSED_TYPE);
    problem.setTitle("Idempotency key reused");
    problem.setDetail(
        "This Idempotency-Key was already used for a different receipt. Nothing was posted."
            + " Use a new key for a new wire, or resubmit the original request unchanged.");
    attachTransferId(problem, e.transferId());
    return problem;
  }

  @ExceptionHandler(CashReceiptInFlightException.class)
  public ProblemDetail inFlight(CashReceiptInFlightException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(IN_FLIGHT_TYPE);
    problem.setTitle("Cash receipt in flight");
    problem.setDetail(
        "Another request for this receipt is in progress. Retry: the movement happens at most"
            + " once, whichever request wins.");
    attachTransferId(problem, e.transferId());
    return problem;
  }

  @ExceptionHandler(CashReceiptRejectedException.class)
  public ProblemDetail rejected(CashReceiptRejectedException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(REJECTED_TYPE);
    problem.setTitle("Cash receipt rejected by the ledger");
    problem.setDetail("The ledger refused this movement and nothing was posted.");
    // Our own rejection vocabulary (LedgerRejectionReason), never a vendor status string.
    problem.setProperty("rejectionReason", e.reason());
    attachTransferId(problem, e.receipt().transferId());
    return problem;
  }

  /**
   * 503, not 500. The distinction is the point of the state: the receipt is recorded, the movement
   * is unknown, and retrying is both safe and the correct action — the transfer id is derived, so a
   * retry either posts it or discovers it was already posted.
   */
  @ExceptionHandler(CashReceiptUnconfirmedException.class)
  public ProblemDetail unconfirmed(CashReceiptUnconfirmedException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    problem.setType(UNCONFIRMED_TYPE);
    problem.setTitle("Cash receipt unconfirmed");
    problem.setDetail(
        "The receipt was recorded but its ledger movement could not be confirmed. Retry with the"
            + " same Idempotency-Key: it will converge on one movement, never two.");
    attachTransferId(problem, e.transferId());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a cash receipt request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }

  private static void attachTransferId(ProblemDetail problem, TransferId transferId) {
    problem.setProperty("transferId", TransferIdDeriver.toUuid(transferId).toString());
  }
}
