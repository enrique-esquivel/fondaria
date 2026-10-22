package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ingestion.InvalidValuationSubmissionException;
import dev.fondaria.valuationapi.ingestion.ValuationBatchNotFoundException;
import dev.fondaria.valuationapi.objectstore.ObjectStoreUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the valuation endpoints (AGENTS.md §7).
 *
 * <p>Only messages this codebase authored are rendered. Anything else becomes a generic {@code 500}
 * saying nothing, because a driver, parser or SDK message can carry a SQL fragment, a hostname, a
 * bucket name or a presigned URL's signature. The real cause is logged.
 *
 * <p><b>A rejected file is not an error here.</b> Whole-batch rejection is an outcome recorded on
 * the batch and read from its status resource, not an HTTP failure — the submission that carried it
 * succeeded. The only failures this handler renders are ones about the call itself.
 */
@RestControllerAdvice(assignableTypes = ValuationController.class)
public class ValuationExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ValuationExceptionHandler.class);

  private static final URI BATCH_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/valuation-batch-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI OBJECT_STORE_TYPE =
      URI.create("https://fondaria.example/problems/object-store-unavailable");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  @ExceptionHandler(ValuationBatchNotFoundException.class)
  public ProblemDetail batchNotFound(ValuationBatchNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(BATCH_NOT_FOUND_TYPE);
    problem.setTitle("Valuation batch not found");
    problem.setDetail("No valuation batch " + e.batchId() + " exists.");
    return problem;
  }

  @ExceptionHandler(InvalidValuationSubmissionException.class)
  public ProblemDetail invalidRequest(InvalidValuationSubmissionException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  /**
   * The ageing report refuses a future {@code asOf}: a date that has not happened cannot be late.
   * Raised as {@link IllegalArgumentException} by the service, and rendered as a {@code 400} with a
   * message authored here rather than the exception's own.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidArgument(IllegalArgumentException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail("asOf must not be in the future.");
    return problem;
  }

  /**
   * 503, not 500. Only the upload-slot call can raise it, and retrying is both safe and the correct
   * action, because issuing a slot creates no state and grants no batch.
   */
  @ExceptionHandler(ObjectStoreUnavailableException.class)
  public ProblemDetail objectStoreUnavailable(ObjectStoreUnavailableException e) {
    LOGGER.error("The object store could not be reached serving a valuation request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    problem.setType(OBJECT_STORE_TYPE);
    problem.setTitle("Object store unavailable");
    problem.setDetail("An upload slot could not be issued. Retry: nothing was created.");
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a valuation request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
