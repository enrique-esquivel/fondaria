package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.common.ParticipantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/participants/{participantId}/balance} — Task 6.2's endpoint, as specified in
 * {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>Thin on purpose: parse the path variable, delegate, map the result. Every decision worth
 * testing is in {@code BalanceService}, and the failure translations are in {@link
 * BalanceExceptionHandler}.
 *
 * <p><b>Unauthenticated in v1</b>, the same position as every other endpoint on this platform, and
 * it must not be exposed outside the cluster until Phase 9/10 lands. A participant's balance is
 * readable by anyone who can reach the port and knows an id — which is the reason the id is opaque
 * and unguessable rather than the reason it is safe.
 *
 * <p><b>The {@code @PathVariable} is named explicitly</b>, as it is on every other controller in
 * this platform: the build does not compile with {@code -parameters}, so an unnamed one resolves to
 * nothing at runtime and every request fails. Caught here by {@code BalanceControllerTest} rather
 * than in a deployment.
 */
@RestController
public class BalanceController {

  private final BalanceService balances;

  public BalanceController(BalanceService balances) {
    this.balances = balances;
  }

  @GetMapping("/v1/participants/{participantId}/balance")
  public ParticipantBalanceResponse getParticipantBalance(
      @PathVariable("participantId") String participantId) {
    ParticipantId id = parse(participantId);
    return ParticipantBalanceResponse.from(balances.balanceOf(id));
  }

  @GetMapping("/v1/participants/{participantId}/positions")
  public ParticipantPositionsResponse getParticipantPositions(
      @PathVariable("participantId") String participantId) {
    ParticipantId id = parse(participantId);
    return ParticipantPositionsResponse.from(balances.balanceOf(id));
  }

  /**
   * Narrows {@code ParticipantId.parse}'s {@link IllegalArgumentException} to a type that means
   * only this. Everything else that raises {@code IllegalArgumentException} downstream is a fault
   * in our own data, not in the caller's request — see {@link MalformedParticipantIdException}.
   */
  private static ParticipantId parse(String participantId) {
    try {
      return ParticipantId.parse(participantId);
    } catch (IllegalArgumentException e) {
      throw new MalformedParticipantIdException(e);
    }
  }
}
