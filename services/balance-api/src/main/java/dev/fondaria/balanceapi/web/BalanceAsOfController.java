package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.asof.AsOfBalanceService;
import dev.fondaria.common.ParticipantId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/participants/{participantId}/balance-as-of/{date}} — Task 6.4's endpoint, as
 * specified in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>Thin on purpose: parse the two path variables, delegate, map the result. Every decision worth
 * testing is in {@code AsOfBalanceService}, and the failure translations are in {@link
 * BalanceAsOfExceptionHandler}.
 *
 * <p><b>Both {@code @PathVariable}s are named explicitly</b>, as on every other controller here:
 * the build does not compile with {@code -parameters}, so an unnamed one resolves to nothing at
 * runtime and every request fails. Caught by {@code BalanceAsOfControllerTest} rather than in a
 * deployment.
 *
 * <p><b>Both are parsed by hand rather than by Spring's converters</b>, for the reason {@code
 * MovementController} gives: Spring's own failure message quotes the caller's input, and that text
 * would reach a problem detail.
 *
 * <p><b>Unauthenticated in v1</b>, the same position as every other endpoint on this platform, and
 * it must not be exposed outside the cluster until Phase 9/10 lands.
 */
@RestController
public class BalanceAsOfController {

  private final AsOfBalanceService balances;

  public BalanceAsOfController(AsOfBalanceService balances) {
    this.balances = balances;
  }

  @GetMapping("/v1/participants/{participantId}/balance-as-of/{date}")
  public ParticipantBalanceAsOfResponse getParticipantBalanceAsOf(
      @PathVariable("participantId") String participantId, @PathVariable("date") String date) {
    ParticipantId pId = parseParticipantId(participantId);
    return ParticipantBalanceAsOfResponse.from(balances.balanceAsOf(pId, parseDate(date)));
  }

  /**
   * Narrows {@code ParticipantId.parse}'s {@link IllegalArgumentException} to a type that means
   * only this — everything else raising that type downstream is a fault in our own data, not in the
   * caller's request. See {@link MalformedParticipantIdException}.
   */
  private static ParticipantId parseParticipantId(String participantId) {
    try {
      return ParticipantId.parse(participantId);
    } catch (IllegalArgumentException e) {
      throw new MalformedParticipantIdException(e);
    }
  }

  /**
   * <b>No upper bound on the date.</b> A date in the future is not rejected here: it resolves the
   * last holding on record and then finds no NAV struck for it, which is answered as "no price on
   * record for that date" — the true reason, and the same answer any other unpriced date gets. A
   * hand-rolled "not in the future" rule would need this service to hold an opinion about what the
   * business date is, which is the demo's simulated clock's business and not a query API's.
   */
  private static LocalDate parseDate(String date) {
    try {
      return LocalDate.parse(date);
    } catch (DateTimeParseException e) {
      throw new MalformedDateException(e);
    }
  }
}
