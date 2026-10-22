package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.ParticipantBalance;
import java.time.Instant;
import java.util.List;

/** {@code ParticipantPositions} in {@code contracts/openapi/balance-api.yaml}. */
public record ParticipantPositionsResponse(
    String participantId, Instant asOf, List<FundBalanceResponse> positions) {

  public static ParticipantPositionsResponse from(ParticipantBalance balance) {
    return new ParticipantPositionsResponse(
        balance.participantId().toString(),
        balance.asOf(),
        balance.positions().stream().map(FundBalanceResponse::from).toList());
  }
}
