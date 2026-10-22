package dev.fondaria.contribution.batch.web;

import dev.fondaria.contribution.batch.ContributionBatchNotFoundException;
import dev.fondaria.contribution.batch.InvalidBatchSubmissionException;
import dev.fondaria.contribution.objectstore.ObjectStoreUnavailableException;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the contribution-batch endpoints (docs/ENGINEERING-RULES.md §7),
 * following the pattern {@code CashReceiptExceptionHandler} sets.
 *
 * <p>Only messages this codebase authored are rendered. Anything else becomes a generic {@code 500}
 * saying nothing, because a driver, parser or SDK message can carry a SQL fragment, a hostname, a
 * bucket name or a presigned URL's signature. The real cause is logged, where the never-log list
 * still applies.
 *
 * <p><b>A rejected file is not an error here.</b> Whole-batch rejection is an outcome recorded on
 * the batch and read from its status resource, not an HTTP failure — the submission that carried it
 * succeeded. The only failures this handler renders are ones about the call itself.
 */
@RestControllerAdvice(assignableTypes = ContributionBatchController.class)
public class ContributionBatchExceptionHandler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ContributionBatchExceptionHandler.class);

  private static final URI ORGANIZATION_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/organization-not-found");
  private static final URI BATCH_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/contribution-batch-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI OBJECT_STORE_TYPE =
      URI.create("https://fondaria.example/problems/object-store-unavailable");
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
    problem.setType(ORGANIZATION_NOT_FOUND_TYPE);
    problem.setTitle("Organization not found");
    problem.setDetail(
        "No organization is registered under id "
            + e.organizationId()
            + ", so no contribution batch can be submitted for it.");
    return problem;
  }

  @ExceptionHandler(ContributionBatchNotFoundException.class)
  public ProblemDetail batchNotFound(ContributionBatchNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(BATCH_NOT_FOUND_TYPE);
    problem.setTitle("Contribution batch not found");
    problem.setDetail("No contribution batch " + e.batchId() + " exists for this organization.");
    return problem;
  }

  @ExceptionHandler(InvalidBatchSubmissionException.class)
  public ProblemDetail invalidRequest(InvalidBatchSubmissionException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  /**
   * 503, not 500. Only the upload-slot call can raise it, and only presigning can fail for this
   * reason; retrying is both safe and the correct action, because issuing a slot creates no state
   * and grants no batch.
   */
  @ExceptionHandler(ObjectStoreUnavailableException.class)
  public ProblemDetail objectStoreUnavailable(ObjectStoreUnavailableException e) {
    LOGGER.error("The object store could not be reached serving a contribution-batch request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    problem.setType(OBJECT_STORE_TYPE);
    problem.setTitle("Object store unavailable");
    problem.setDetail("An upload slot could not be issued. Retry: nothing was created.");
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a contribution-batch request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
