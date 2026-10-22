package dev.fondaria.contribution.organization.web;

import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the organization endpoints (AGENTS.md §7).
 *
 * <p>Only messages this codebase authored are rendered. Anything else — a driver error, a parser
 * error, an unchecked bug — becomes a generic {@code 500} whose detail says nothing, because such a
 * message can carry a SQL fragment, a hostname or the very tax identifier that must not leave the
 * process. The real cause is logged, where the never-log list still applies.
 */
@RestControllerAdvice(assignableTypes = OrganizationController.class)
public class OrganizationExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrganizationExceptionHandler.class);

  private static final URI NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/organization-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
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
  public ProblemDetail notFound(OrganizationNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(NOT_FOUND_TYPE);
    problem.setTitle("Organization not found");
    // The id is the opaque derived value, safe to render — it is not the tax identifier.
    problem.setDetail("No organization is registered under id " + e.organizationId());
    return problem;
  }

  @ExceptionHandler(InvalidOrganizationRequestException.class)
  public ProblemDetail invalidRequest(InvalidOrganizationRequestException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving an organization request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
