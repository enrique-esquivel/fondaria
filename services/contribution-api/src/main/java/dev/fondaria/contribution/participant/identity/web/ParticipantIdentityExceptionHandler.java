package dev.fondaria.contribution.participant.identity.web;

import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import dev.fondaria.contribution.participant.identity.IdentityKeyDestroyedException;
import dev.fondaria.contribution.participant.identity.InvalidIdentityCaptureRequestException;
import dev.fondaria.contribution.participant.identity.ParticipantIdentityNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for {@link ParticipantIdentityController}, following the pattern {@code
 * ParticipantLookupExceptionHandler} sets.
 */
@RestControllerAdvice(assignableTypes = ParticipantIdentityController.class)
public class ParticipantIdentityExceptionHandler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ParticipantIdentityExceptionHandler.class);

  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/participant-not-found");
  private static final URI KEY_DESTROYED_TYPE =
      URI.create("https://fondaria.example/problems/identity-key-destroyed");
  private static final URI FORBIDDEN_TYPE =
      URI.create("https://fondaria.example/problems/forbidden");
  private static final URI UNAUTHORIZED_TYPE =
      URI.create("https://fondaria.example/problems/unauthorized");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

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

  @ExceptionHandler(InvalidIdentityPathException.class)
  public ProblemDetail invalidPath(InvalidIdentityPathException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(InvalidIdentityCaptureRequestException.class)
  public ProblemDetail invalidRequest(InvalidIdentityCaptureRequestException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(INVALID_REQUEST_TYPE);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(ParticipantNotFoundException.class)
  public ProblemDetail participantNotFound(ParticipantNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(NOT_FOUND_TYPE);
    problem.setTitle("Participant not found");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(ParticipantIdentityNotFoundException.class)
  public ProblemDetail identityNotFound(ParticipantIdentityNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(NOT_FOUND_TYPE);
    problem.setTitle("Identity not found");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(IdentityKeyDestroyedException.class)
  public ProblemDetail keyDestroyed(IdentityKeyDestroyedException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
    problem.setType(KEY_DESTROYED_TYPE);
    problem.setTitle("Identity key destroyed");
    problem.setDetail(e.getMessage());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a participant identity request", e);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Internal error");
    problem.setDetail("The request could not be processed.");
    return problem;
  }
}
