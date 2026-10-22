package dev.fondaria.contribution.plan.web;

import dev.fondaria.contribution.fund.FundNotFoundException;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the plan-version endpoint (AGENTS.md §7), following the pattern
 * {@code OrganizationExceptionHandler} sets.
 */
@RestControllerAdvice(assignableTypes = PlanVersionController.class)
public class PlanVersionExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlanVersionExceptionHandler.class);

  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI CONFLICT_TYPE =
      URI.create("https://fondaria.example/problems/plan-version-conflict");
  private static final URI ORGANIZATION_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/organization-not-found");
  private static final URI FUND_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/fund-not-found");
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

  @ExceptionHandler(InvalidPlanVersionRequestException.class)
  public ProblemDetail invalidRequest(InvalidPlanVersionRequestException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(PlanVersionConflictException.class)
  public ProblemDetail conflict(PlanVersionConflictException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(CONFLICT_TYPE);
    problem.setTitle("Plan version conflict");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(OrganizationNotFoundException.class)
  public ProblemDetail organizationNotFound(OrganizationNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(ORGANIZATION_NOT_FOUND_TYPE);
    problem.setTitle("Organization not found");
    problem.setDetail("No organization is registered under id " + e.organizationId());
    return problem;
  }

  @ExceptionHandler(FundNotFoundException.class)
  public ProblemDetail fundNotFound(FundNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(FUND_NOT_FOUND_TYPE);
    problem.setTitle("Fund not found");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a plan version request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
