package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.asof.AsOfPosition;

/**
 * One fund's contribution to a participant's balance on a past date — {@code
 * ParticipantBalanceAsOfPosition} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>One units figure, vested and unvested combined.</b> Unlike the current balance, which keeps
 * the two lots apart because both are live claims a participant is entitled to tell apart
 * (ADR-0004), the historical view renders a single quantity per fund — and {@code valueCents} is
 * that quantity times the rendered NAV, rounded once, so a reader who repeats the multiplication
 * lands on the same centavo (ADR-0013).
 *
 * <p>Units at scale 1e-8, cash in MXN cents, both as plain JSON integers (AGENTS.md §4).
 */
public record AsOfPositionResponse(
    String fundId, long unitsScaled, long valueCents, AsOfFundNavResponse nav) {

  public static AsOfPositionResponse from(AsOfPosition position) {
    return new AsOfPositionResponse(
        position.fundId().toString(),
        position.units().scaled(),
        position.value().cents(),
        AsOfFundNavResponse.from(position));
  }
}
