package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.asof.AsOfBalance;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code ParticipantBalanceAsOf} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>{@code date} echoes what was asked for, not what was found.</b> The holdings behind it may
 * have last changed weeks earlier — that is the whole point of a sparse projection — and a response
 * that quietly reported the change date instead would answer a question the caller did not ask.
 *
 * <p>{@code valueCents} is exactly the sum of {@code positions[].valueCents}: already-rounded
 * per-fund figures added exactly, never a re-multiplication of summed units (ADR-0013).
 *
 * <p>An empty {@code positions} with {@code valueCents: 0} is a real answer — everything had been
 * redeemed by that date. "No record this far back" is a {@code 404} instead, and never this.
 */
public record ParticipantBalanceAsOfResponse(
    String participantId, LocalDate date, long valueCents, List<AsOfPositionResponse> positions) {

  public static ParticipantBalanceAsOfResponse from(AsOfBalance balance) {
    return new ParticipantBalanceAsOfResponse(
        balance.participantId().toString(),
        balance.date(),
        balance.value().cents(),
        balance.positions().stream().map(AsOfPositionResponse::from).toList());
  }
}
