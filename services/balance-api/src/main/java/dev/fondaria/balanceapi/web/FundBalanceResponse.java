package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.FundPosition;

/**
 * One fund's contribution to a participant's balance — {@code FundBalance} in {@code
 * contracts/openapi/balance-api.yaml}.
 *
 * <p>Units at scale 1e-8, cash in MXN cents, both as plain JSON integers (docs/ENGINEERING-RULES.md
 * §4).
 *
 * <p><b>Unvested is reported, not hidden.</b> Those units are issued and priced; vesting decides
 * what the participant is entitled to take, not what their holding is worth (ADR-0004). Showing the
 * two separately is what lets a participant see both numbers without either being misleading.
 */
public record FundBalanceResponse(
    String fundId,
    long vestedUnitsScaled,
    long unvestedUnitsScaled,
    FundNavResponse nav,
    long vestedValueCents,
    long unvestedValueCents,
    long valueCents) {

  public static FundBalanceResponse from(FundPosition position) {
    return new FundBalanceResponse(
        position.fundId().toString(),
        position.vestedUnits().scaled(),
        position.unvestedUnits().scaled(),
        FundNavResponse.from(position.nav()),
        position.vestedValue().cents(),
        position.unvestedValue().cents(),
        position.value().cents());
  }
}
