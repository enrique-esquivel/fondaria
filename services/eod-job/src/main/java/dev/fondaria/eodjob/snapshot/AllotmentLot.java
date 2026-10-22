package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.Units;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import java.time.Instant;
import java.util.Objects;

/**
 * One unit issuance that a position is built out of: units, the NAV they were issued at, and when
 * the issuance was posted.
 *
 * <p>{@code navScaled} is the price on the {@code reference.fund_nav} row the allotment itself
 * named, at 1e-8 — <b>not</b> the live price for that date. If that NAV was later corrected, the
 * lot still cost what it cost; a correction changes what the units are worth today, which is the
 * position's {@link SnapshotPosition#value()}, and never what was paid for them.
 *
 * <p>{@code allottedAt} is what decides whether the lot belongs to a snapshot — see {@link
 * CostBasisSource}. It is the instant allotment-job posted the chain, which is the same event
 * {@code projection.participant_units.effective_date} is derived from, and deliberately not the
 * NAV's valuation date.
 */
public record AllotmentLot(HoldingKey holding, Units units, long navScaled, Instant allottedAt) {

  public AllotmentLot {
    Objects.requireNonNull(holding, "holding must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(allottedAt, "allottedAt must not be null");
    if (navScaled <= 0) {
      throw new IllegalArgumentException("navScaled must be positive");
    }
  }
}
