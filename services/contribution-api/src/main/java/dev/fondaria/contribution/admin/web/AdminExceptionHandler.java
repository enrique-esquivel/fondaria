package dev.fondaria.contribution.admin.web;

import dev.fondaria.common.security.AccessDeniedProblemException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdminExceptionHandler.class);

  private static final URI FORBIDDEN_TYPE =
      URI.create("https://fondaria.example/problems/forbidden");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
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

  @ExceptionHandler(AccessDeniedProblemException.class)
  public ProblemDetail accessDenied(AccessDeniedProblemException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(FORBIDDEN_TYPE);
    problem.setTitle("Forbidden");
    problem.setDetail(e.detail());
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail badRequest(IllegalArgumentException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving an admin request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
