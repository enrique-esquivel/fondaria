package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.balance.ParticipantBalance;
import java.time.Instant;
import java.util.List;

/**
 * {@code ParticipantBalance} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p><b>{@code investedValueCents} is exactly the sum of {@code positions[].valueCents}</b>, and
 * carries nothing else. Cash awaiting allotment is deliberately not reported to a participant:
 * until a dispersion file has arrived and passed the reconciliation gate there is no
 * per-participant split of it to report, and a provisional figure that can still change is worse
 * than none.
 *
 * <p>A plain JSON integer of MXN cents (docs/ENGINEERING-RULES.md §4).
 *
 * @param stale {@code true} when at least one position's price is older than the configured age.
 *     Served marked, never silently, and never withheld.
 */
public record ParticipantBalanceResponse(
    String participantId,
    Instant asOf,
    long investedValueCents,
    List<FundBalanceResponse> positions,
    boolean stale) {

  public static ParticipantBalanceResponse from(ParticipantBalance balance) {
    return new ParticipantBalanceResponse(
        balance.participantId().toString(),
        balance.asOf(),
        balance.investedValue().cents(),
        balance.positions().stream().map(FundBalanceResponse::from).toList(),
        balance.stale());
  }
}
