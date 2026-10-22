package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.Units;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of {@code projection.participant_units}: what a single holding account held, as at the
 * last date it changed on or before the date asked about.
 *
 * <p><b>An account, not a participant.</b> {@code participant_units.participant_id} is the holding
 * account's own u128 id (Task 5.5) — vested and unvested are separate ledger accounts and therefore
 * separate series, with their own change dates. Combining them is the service's job, not the
 * repository's.
 *
 * <p><b>The row's own {@code fund_id} is read back rather than assumed from the branch that asked
 * for it.</b> A holding account belongs to exactly one fund, so the two can only disagree through a
 * CDC defect or an account-id collision — and if they ever do, the caller matches the pair against
 * what it derived and fails loudly, instead of merging two funds' units into one and overstating
 * one position while understating another.
 *
 * @param account the holding account and the unit ledger the row was stored under, as the row
 *     itself carries them
 * @param effectiveDate the value date of the change that produced this holding, never the date the
 *     caller asked about. Carried so a caller can see the gap it is reading across.
 */
public record HistoricHolding(HoldingAccountKey account, LocalDate effectiveDate, Units units) {

  public HistoricHolding {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
    Objects.requireNonNull(units, "units must not be null");
  }
}
