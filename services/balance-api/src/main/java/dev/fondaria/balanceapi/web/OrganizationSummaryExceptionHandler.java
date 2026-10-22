package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.PricesUnavailableException;
import dev.fondaria.balanceapi.summary.OrganizationNotFoundException;
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
 * RFC 9457 problem details for the organization summary (AGENTS.md §7).
 *
 * <p>Only messages this codebase authored are rendered — never an exception's own, which can carry
 * a SQL fragment, a hostname or an identifier. The real cause is logged.
 *
 * <p><b>No failure here is ever rendered as a zero summary.</b> An employer seeing {@code 0} across
 * every fund would read it as their fund being empty and an operator would read it as a working
 * system; both are worse than an error. This is the same rule {@code BalanceExceptionHandler}
 * states for one participant, and it matters more, not less, at an employer's scale.
 *
 * <p><b>{@code instance} is the route template, never the concrete request URI.</b> Spring fills
 * that field from the actual path when it is absent, and this route carries an organization
 * identifier — which is derived from a tax identifier and stays out of error bodies an intermediary
 * might store, exactly as {@code AGENTS.md} §7 keeps the tax identifier itself out of every URL.
 */
@RestControllerAdvice(assignableTypes = OrganizationSummaryController.class)
public class OrganizationSummaryExceptionHandler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(OrganizationSummaryExceptionHandler.class);

  private static final URI ORGANIZATION_NOT_FOUND_TYPE =
      URI.create("https://fondaria.example/problems/organization-not-found");
  private static final URI INVALID_REQUEST_TYPE =
      URI.create("https://fondaria.example/problems/invalid-request");
  private static final URI LEDGER_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/ledger-unavailable");
  private static final URI PRICES_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/prices-unavailable");
  private static final URI PROJECTION_UNAVAILABLE_TYPE =
      URI.create("https://fondaria.example/problems/projection-unavailable");
  private static final URI INTERNAL_TYPE =
      URI.create("https://fondaria.example/problems/internal-error");

  /** The route with the organization elided, not the request. Braces are not legal in a URI. */
  private static final URI ROUTE = URI.create("/v1/organizations/-/summary");

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

  /** Never echoes the id: it came from a URL and is derived from a tax identifier. */
  @ExceptionHandler(OrganizationNotFoundException.class)
  public ProblemDetail organizationNotFound(OrganizationNotFoundException e) {
    return problem(
        HttpStatus.NOT_FOUND,
        ORGANIZATION_NOT_FOUND_TYPE,
        "Organization not found",
        "No organization exists for the requested identifier.");
  }

  @ExceptionHandler(MalformedOrganizationIdException.class)
  public ProblemDetail invalidRequest(MalformedOrganizationIdException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST_TYPE,
        "Invalid request",
        "organizationId is not a valid UUID.");
  }

  /**
   * 503, and never a partial total.
   *
   * <p>The ledger is the authority for what an organization's participants hold. A page of the
   * roster that could not be read is not "those participants hold nothing" — it is an outage, and
   * an aggregate missing an unknown number of people is the one answer worse than no answer.
   * Retrying is safe: a read creates nothing.
   */
  @ExceptionHandler(LedgerUnavailableException.class)
  public ProblemDetail ledgerUnavailable(LedgerUnavailableException e) {
    LOGGER.error("The ledger could not be reached serving an organization summary", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        LEDGER_UNAVAILABLE_TYPE,
        "Ledger unavailable",
        "The summary could not be read. Retry: nothing was changed.");
  }

  @ExceptionHandler(PricesUnavailableException.class)
  public ProblemDetail pricesUnavailable(PricesUnavailableException e) {
    LOGGER.error("The NAV cache holds no prices; refusing to serve an organization summary", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PRICES_UNAVAILABLE_TYPE,
        "Prices unavailable",
        "No valuation is currently available. Retry: nothing was changed.");
  }

  /**
   * 503, not 500 and never an empty summary.
   *
   * <p>Unlike the balance read, this endpoint depends on PostgreSQL on every request: the roster it
   * aggregates and the contributions figure both live there. A database outage is a dependency
   * being down, not this service being broken, and the retry is safe.
   */
  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail projectionUnavailable(DataAccessException e) {
    LOGGER.error("A database read failed serving an organization summary", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PROJECTION_UNAVAILABLE_TYPE,
        "Summary data unavailable",
        "The summary could not be read. Retry: nothing was changed.");
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    LOGGER.error("Unhandled failure serving an organization summary", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        INTERNAL_TYPE,
        "Internal error",
        "The request could not be processed.");
  }
}
