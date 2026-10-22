package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.asof.FundRegistryUnavailableException;
import dev.fondaria.balanceapi.asof.NoHoldingOnRecordException;
import dev.fondaria.balanceapi.asof.PriceNotOnRecordException;
import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the balance-as-of endpoint (docs/ENGINEERING-RULES.md §7).
 *
 * <p>Its own advice rather than an extension of {@link BalanceExceptionHandler}, for the reason
 * {@link MovementExceptionHandler} gives: the routes fail in different ways — this one never
 * touches the ledger or the NAV cache, and has two distinct reasons to say "not found" that the
 * current balance does not have — and {@code instance} must name the right route.
 *
 * <p><b>No failure here is ever rendered as a zero balance.</b> That is this class's single most
 * important property, and it matters more on a historical read than on a live one: a participant
 * shown {@code 0} for a date before their records begin has no way to tell that from having lost
 * the money.
 *
 * <p>Only messages this codebase authored are rendered — never an exception's own, which can carry
 * a SQL fragment, a hostname or a caller-supplied string.
 *
 * <p><b>{@code instance} is the route template, never the concrete request URI.</b> Spring fills
 * that field from the actual path when it is absent, and this path carries a participant identifier
 * and, on a malformed request, the caller's raw input.
 */
@RestControllerAdvice(assignableTypes = BalanceAsOfController.class)
public class BalanceAsOfExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(BalanceAsOfExceptionHandler.class);

  private static final URI PARTICIPANT_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/participant-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI NO_HOLDING_ON_RECORD_TYPE =
      URI.create("https://fondaria.example/problems/no-holding-on-record");
  private static final URI PRICE_NOT_ON_RECORD_TYPE =
      URI.create("https://fondaria.example/problems/price-not-on-record");
  private static final URI FUND_REGISTRY_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/fund-registry-unavailable");
  private static final URI PROJECTION_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/historical-balance-unavailable");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  /** {@code -} rather than {@code {participantId}}: braces are not legal in a URI. */
  private static final URI ROUTE = URI.create("/v1/participants/-/balance-as-of/-");

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
   * 404, and emphatically not {@code 200} with a zero balance.
   *
   * <p>A distinct {@code type} from "participant not found" so a client can tell the two apart: the
   * first says the id is wrong, this one says the id is right and the date is earlier than anything
   * on record. The detail says which, because a caller who cannot tell them apart will retry the
   * wrong thing.
   */
  @ExceptionHandler(NoHoldingOnRecordException.class)
  public ProblemDetail noHoldingOnRecord(NoHoldingOnRecordException e) {
    return problem(
        HttpStatus.NOT_FOUND,
        NO_HOLDING_ON_RECORD_TYPE,
        "No holding on record",
        "No holding is recorded for this participant on or before the requested date. This is not"
            + " a zero balance: their records do not reach that far back.");
  }

  /**
   * 404, and never a partial total.
   *
   * <p>The participant held units in a fund that has no price for the date. Reporting the funds
   * that did have one would understate their holdings with nothing on the response saying so. The
   * fund is logged for the operator who has to strike the missing price; it is not in the body,
   * where it would tell a caller which funds a participant holds.
   */
  @ExceptionHandler(PriceNotOnRecordException.class)
  public ProblemDetail priceNotOnRecord(PriceNotOnRecordException e) {
    LOGGER.error(
        "No live NAV is on record for fund {} on a requested historical date; refusing to serve a"
            + " partial balance",
        e.fundId(),
        e);
    return problem(
        HttpStatus.NOT_FOUND,
        PRICE_NOT_ON_RECORD_TYPE,
        "Price not on record",
        "No valuation is on record for the requested date, so the holding on that date cannot be"
            + " valued.");
  }

  /** A path variable that is not a UUID — and only that. Narrowed as it is on the other routes. */
  @ExceptionHandler(MalformedParticipantIdException.class)
  public ProblemDetail invalidParticipantId(MalformedParticipantIdException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST_TYPE,
        "Invalid request",
        "participantId is not a valid UUID.");
  }

  /** A path variable that is not an ISO-8601 date. The caller's text is never echoed. */
  @ExceptionHandler(MalformedDateException.class)
  public ProblemDetail invalidDate(MalformedDateException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST_TYPE,
        "Invalid request",
        "date is not a valid ISO-8601 date (YYYY-MM-DD).");
  }

  /**
   * 503, and deliberately not the {@code 404} next to it.
   *
   * <p>An empty fund registry is a platform fault — the wrong database, a restore that has not
   * replayed {@code reference} yet — and says nothing about the participant. Reporting it as "no
   * record this far back" would be a falsehood about every participant on every date.
   */
  @ExceptionHandler(FundRegistryUnavailableException.class)
  public ProblemDetail fundRegistryUnavailable(FundRegistryUnavailableException e) {
    LOGGER.error("No fund is registered; refusing to answer a historical balance", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        FUND_REGISTRY_UNAVAILABLE_TYPE,
        "Fund registry unavailable",
        "The fund registry could not be read. Retry: nothing was changed.");
  }

  /**
   * 503, never an empty or zero answer.
   *
   * <p>Unlike a current balance, this endpoint is a PostgreSQL read by its nature — the ledger
   * cannot answer "as of" at all. When the projection or the NAV store cannot be read there is no
   * historical balance to report, and zero is a claim about somebody's money rather than about a
   * database. Retrying is safe: a read changes nothing.
   */
  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail projectionUnavailable(DataAccessException e) {
    LOGGER.error("A historical balance could not be read", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PROJECTION_UNAVAILABLE_TYPE,
        "Historical balance unavailable",
        "The balance for that date could not be read. Retry: nothing was changed.");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving a historical balance request", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        INTERNAL_TYPE,
        "Internal error",
        "The request could not be processed.");
  }
}
