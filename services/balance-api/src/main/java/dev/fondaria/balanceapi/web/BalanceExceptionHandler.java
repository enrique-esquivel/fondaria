package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.balance.PricesUnavailableException;
import dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the balance endpoint (docs/ENGINEERING-RULES.md §7).
 *
 * <p>Only messages this codebase authored are rendered — never an exception's own, which can carry
 * a SQL fragment, a hostname or a participant identifier. The real cause is logged.
 *
 * <p><b>No failure here is ever rendered as a zero balance.</b> That is the single most important
 * property of this class: a participant seeing {@code 0} when the ledger is unreachable would read
 * it as "my money is gone", and an operator would read it as a working system. Both are worse than
 * an error.
 *
 * <p><b>{@code instance} is the route template, never the concrete request URI.</b> Spring
 * populates that field from the actual path when it is left absent, and this route's path carries a
 * participant identifier — so the default would put an identifier for a person into every error
 * body an intermediary might store, and on a malformed request would reflect the caller's raw input
 * back verbatim. The template says everything {@code instance} is for without either.
 */
@RestControllerAdvice(assignableTypes = BalanceController.class)
public class BalanceExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(BalanceExceptionHandler.class);

  private static final URI PARTICIPANT_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/participant-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI LEDGER_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/ledger-unavailable");
  private static final URI PRICES_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/prices-unavailable");
  private static final URI DIRECTORY_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/participant-directory-unavailable");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  /**
   * The route with the participant elided, not the request. See this class's javadoc.
   *
   * <p>{@code -} rather than {@code {participantId}} because braces are not legal in a URI and
   * {@link URI#create} rejects them.
   */
  private static final URI ROUTE = URI.create("/v1/participants/-/balance");

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
   * A path variable that is not a UUID — and <b>only</b> that.
   *
   * <p>Deliberately not {@code @ExceptionHandler(IllegalArgumentException.class)}, which is what
   * this was until an adversarial review caught it. {@code ParticipantScope} and {@code
   * AccountIdDeriver} raise that same type for a blank {@code participant_internal_ref} — a
   * data-integrity fault in {@code master.participant}, whose column is {@code NOT NULL} but
   * carries no non-blank {@code CHECK}. Matching it here reported that incident as the caller's
   * malformed UUID, where it would page nobody and be closed as a bad request. It now falls through
   * to {@link #unexpected} and is logged as the {@code 500} it is.
   */
  @ExceptionHandler(MalformedParticipantIdException.class)
  public ProblemDetail invalidRequest(MalformedParticipantIdException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST_TYPE,
        "Invalid request",
        "participantId is not a valid UUID.");
  }

  /**
   * 503, and never a partial answer.
   *
   * <p>The ledger is the authority for a balance; if it cannot be reached there is no balance to
   * report, only an outage to declare. Retrying is safe because a read creates nothing. A database
   * outage does <i>not</i> produce this — nothing on this path reads PostgreSQL once a participant
   * has been resolved.
   */
  @ExceptionHandler(LedgerUnavailableException.class)
  public ProblemDetail ledgerUnavailable(LedgerUnavailableException e) {
    LOGGER.error("The ledger could not be reached serving a balance request", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        LEDGER_UNAVAILABLE_TYPE,
        "Ledger unavailable",
        "The balance could not be read. Retry: nothing was changed.");
  }

  /**
   * 503, not 200-with-zero.
   *
   * <p>No prices means this instance cannot value anything, which is not the same claim as "the
   * participant holds nothing" — and serving the second when the first is true is the single worst
   * answer this endpoint could give.
   */
  @ExceptionHandler(PricesUnavailableException.class)
  public ProblemDetail pricesUnavailable(PricesUnavailableException e) {
    LOGGER.error("The NAV cache holds no prices; refusing to serve a balance", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PRICES_UNAVAILABLE_TYPE,
        "Prices unavailable",
        "No valuation is currently available. Retry: nothing was changed.");
  }

  /**
   * 503, and the one case where a PostgreSQL outage is visible to a caller.
   *
   * <p>Reached only for a participant this instance has never resolved before — the id-to-scope
   * mapping is immutable and cached, so anyone already seen is served from the ledger alone. Not a
   * {@code 500}: the service is not broken, a dependency is down and the retry is safe. Previously
   * this fell through to {@link #unexpected}, which contradicted what three documents promised.
   */
  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail directoryUnavailable(DataAccessException e) {
    LOGGER.error("The participant directory could not be read serving a balance request", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        DIRECTORY_UNAVAILABLE_TYPE,
        "Participant directory unavailable",
        "The participant could not be resolved. Retry: nothing was changed.");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a balance request", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        INTERNAL_TYPE,
        "Internal error",
        "The request could not be processed.");
  }
}
