package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.movement.InvalidMovementQueryException;
import dev.fondaria.balanceapi.movement.TamperedCursorException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the movement-history endpoint (docs/ENGINEERING-RULES.md §7).
 *
 * <p>Its own advice rather than an extension of {@link BalanceExceptionHandler}: the two routes
 * fail in different ways — a history never touches the ledger or a price, and a balance has no
 * cursor — and {@code instance} must name the right route. The shared shape is a handful of lines;
 * merging them would couple two endpoints' error contracts to save those lines.
 *
 * <p>Only messages this codebase authored are rendered — never an exception's own where it could
 * carry a SQL fragment, a hostname or a caller-supplied string. {@link
 * InvalidMovementQueryException}'s message is an exception in name only: it is chosen from fixed
 * text in {@code MovementController} and never assembled from input.
 *
 * <p><b>{@code instance} is the route template, never the concrete request URI.</b> Spring fills
 * that field from the actual path when it is absent, and this path carries a participant identifier
 * and the caller's own query string.
 */
@RestControllerAdvice(assignableTypes = MovementController.class)
public class MovementExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(MovementExceptionHandler.class);

  private static final URI PARTICIPANT_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/participant-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI INVALID_CURSOR_TYPE =
      URI.create("https://fondaria.example/problems/invalid-cursor");
  private static final URI PROJECTION_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/movement-history-unavailable");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  /** {@code -} rather than {@code {participantId}}: braces are not legal in a URI. */
  private static final URI ROUTE = URI.create("/v1/participants/-/movements");

  private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(type);
    problem.setTitle(title);
    problem.setDetail(detail);
    problem.setInstance(ROUTE);
    return problem;
  }

  private static final URI FORBIDDEN_TYPE =
      URI.create("https://fondaria.example/problems/forbidden");
  private static final URI UNAUTHORIZED_TYPE =
      URI.create("https://fondaria.example/problems/unauthorized");

  @ExceptionHandler(dev.fondaria.common.security.AuthenticationRequiredException.class)
  public ProblemDetail unauthorized(
      dev.fondaria.common.security.AuthenticationRequiredException e) {
    return problem(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_TYPE, "Unauthorized", e.getMessage());
  }

  @ExceptionHandler(dev.fondaria.common.security.AccessDeniedProblemException.class)
  public ProblemDetail accessDenied(dev.fondaria.common.security.AccessDeniedProblemException e) {
    return problem(HttpStatus.FORBIDDEN, FORBIDDEN_TYPE, "Forbidden", e.detail());
  }

  /**
   * Never echoes the id: it came from a URL and identifies a person (docs/ENGINEERING-RULES.md §7).
   */
  @ExceptionHandler(ParticipantNotFoundException.class)
  public ProblemDetail participantNotFound(ParticipantNotFoundException e) {
    return problem(
        HttpStatus.NOT_FOUND,
        PARTICIPANT_NOT_FOUND_TYPE,
        "Participant not found",
        "No participant exists for the requested identifier.");
  }

  /**
   * A path variable that is not a UUID — and only that. Narrowed for the same reason {@link
   * BalanceExceptionHandler} narrows it: {@code ParticipantScope} and {@code AccountIdDeriver}
   * raise {@link IllegalArgumentException} for a blank {@code participant_internal_ref}, which is a
   * fault in our own data and must not be reported as the caller's malformed UUID.
   */
  @ExceptionHandler(MalformedParticipantIdException.class)
  public ProblemDetail invalidParticipantId(MalformedParticipantIdException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST_TYPE,
        "Invalid request",
        "participantId is not a valid UUID.");
  }

  /** A query parameter this service will not accept. The reason is our text, not the caller's. */
  @ExceptionHandler(InvalidMovementQueryException.class)
  public ProblemDetail invalidQuery(InvalidMovementQueryException e) {
    return problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TYPE, "Invalid request", e.reason());
  }

  /**
   * 400, and deliberately the same answer for every way a cursor can fail.
   *
   * <p>A client never constructs a cursor, so the only ways to arrive here are a token from a
   * different query, one from before a secret rotation, or one somebody edited. Saying which would
   * turn the endpoint into a hint service for forging the next attempt.
   */
  @ExceptionHandler(TamperedCursorException.class)
  public ProblemDetail invalidCursor(TamperedCursorException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_CURSOR_TYPE,
        "Invalid cursor",
        "cursor is not a continuation token this service issued for this query. Start a new page.");
  }

  /**
   * 503, never an empty page.
   *
   * <p>Unlike a balance, a history is a PostgreSQL read by its nature — the ledger cannot answer
   * "what happened between these dates" at all. When the projection cannot be read there is no
   * history to report, and "no movements" is a claim about a participant's life, not about a
   * database. Retrying is safe: a read changes nothing.
   */
  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail projectionUnavailable(DataAccessException e) {
    LOGGER.error("The movement projection could not be read serving a history request", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PROJECTION_UNAVAILABLE_TYPE,
        "Movement history unavailable",
        "The movement history could not be read. Retry: nothing was changed.");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a movement history request", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        INTERNAL_TYPE,
        "Internal error",
        "The request could not be processed.");
  }
}
