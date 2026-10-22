package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.ParticipantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A participant's holdings on a past date, and what they were worth that day.
 *
 * <p><b>A date, not an instant.</b> Unlike the current balance, nothing here is read live: units
 * come from the last projected change at or before the date and the price is that date's struck
 * NAV, so the answer is a function of the date alone and is stable on re-reading — apart from a
 * later NAV correction, which is exactly what {@code AsOfPosition.corrected()} exposes.
 *
 * <p><b>Signed, like every value on this platform.</b> Nothing clamps: a fund whose NAV fell
 * produces a smaller value, and that is the correct answer (docs/ENGINEERING-RULES.md §4).
 *
 * <p>An empty {@code positions} list with a zero value is a real answer — the participant held
 * something once and had redeemed it all by this date. It is deliberately distinct from the {@code
 * 404} that says there is no record this far back; see {@link NoHoldingOnRecordException}.
 */
public record AsOfBalance(
    ParticipantId participantId, LocalDate date, Money value, List<AsOfPosition> positions) {

  public AsOfBalance {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(date, "date must not be null");
    Objects.requireNonNull(value, "value must not be null");
    positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
  }

  /**
   * Sums {@code positions} into the headline figure.
   *
   * <p>Exact addition of already-rounded per-fund values, never a re-multiplication of summed
   * units: the rendered total is the sum of the rendered parts (ADR-0013).
   */
  public static AsOfBalance of(
      ParticipantId participantId, LocalDate date, List<AsOfPosition> positions) {
    Money total =
        positions.stream()
            .map(AsOfPosition::value)
            .reduce(Money.of(0, CurrencyCode.MXN), Money::plus);
    return new AsOfBalance(participantId, date, total, positions);
  }
}
