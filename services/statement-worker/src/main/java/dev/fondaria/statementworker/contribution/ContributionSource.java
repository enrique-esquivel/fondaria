package dev.fondaria.statementworker.contribution;

import dev.fondaria.statementworker.ParticipantKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * The statement's contributions line (Task 7.5b, {@code ADR-0022} §4): {@code Σ Money.value(units,
 * strike NAV)} over a participant's {@code workflow.allotment} rows for the period, by source.
 */
public interface ContributionSource {

  /**
   * Every participant with at least one allotment struck in {@code (openingBusinessDate,
   * closingBusinessDate]} — exclusive of the prior period's closing date (already counted in that
   * period), inclusive of this period's, summed by source.
   *
   * <p>Whole-platform, not scoped to one organization: the month-end fan-out processes every
   * organization's close in one pass, matching the snapshot files it reads alongside this.
   *
   * @param postedBefore the read-consistency cutoff: an allotment written at or after this instant
   *     is excluded even if its NAV's {@code valuation_date} falls inside the range. This is the
   *     closing snapshot's own {@code started_at} — an allotment the close's pass had not yet seen
   *     is not reflected in that snapshot's units either, so counting it here would add a
   *     contribution the closing balance does not contain, and the market-movement plug would
   *     silently absorb the gap as a fabricated loss.
   */
  Map<ParticipantKey, ContributionTotals> totalsFor(
      LocalDate openingBusinessDate, LocalDate closingBusinessDate, Instant postedBefore);
}
