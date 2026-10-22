package dev.fondaria.balanceapi.balance;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.ParticipantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A participant's current holdings across every fund, and what they are worth right now.
 *
 * <p><b>{@code investedValue} is the sum of the positions, and there is nothing else in it.</b> No
 * cash awaiting allotment, no declared-but-unreconciled contribution, no accrual — only units the
 * participant actually holds, at the live price. Money in flight has no per-participant split until
 * a dispersion file has arrived and reconciled, and a provisional figure that can still change is
 * worse than none; the employer-facing view of it is the contribution batch's own status resource
 * (Task 21.1).
 *
 * <p><b>Signed, like every value on this platform.</b> Nothing here clamps: a fund whose NAV has
 * fallen produces a smaller value and that is the correct answer (AGENTS.md §4).
 *
 * @param asOf when this was computed. An instant, not a business date — both units and price are
 *     read live.
 * @param stale {@code true} when any position's price is older than the configured age. Surfaced at
 *     the top so a caller cannot miss it by not iterating positions; the per-fund flags say which.
 */
public record ParticipantBalance(
    ParticipantId participantId,
    Instant asOf,
    Money investedValue,
    List<FundPosition> positions,
    boolean stale) {

  public ParticipantBalance {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(asOf, "asOf must not be null");
    Objects.requireNonNull(investedValue, "investedValue must not be null");
    positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
  }

  /**
   * Sums {@code positions} into the headline figure and lifts the staleness flag.
   *
   * <p>A participant holding nothing is {@code Money.zero(MXN)} and an empty list — a real answer,
   * distinct from the {@code 404} that says no such participant exists.
   */
  public static ParticipantBalance of(
      ParticipantId participantId, Instant asOf, List<FundPosition> positions) {
    Money invested =
        positions.stream()
            .map(FundPosition::value)
            .reduce(Money.of(0, CurrencyCode.MXN), Money::plus);
    boolean stale = positions.stream().anyMatch(position -> position.nav().stale());
    return new ParticipantBalance(participantId, asOf, invested, positions, stale);
  }
}
