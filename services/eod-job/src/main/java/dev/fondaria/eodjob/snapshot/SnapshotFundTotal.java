package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * One fund's line in the manifest, and the evidence for the check that proves the snapshot
 * complete.
 *
 * <p>phase-07 Task 7.4 states it in one sentence: <i>{@code Σ units} must equal {@code
 * units_in_issue} per fund — that single check proves the file complete and consistent.</i> It is a
 * strong check because the two numbers travel entirely different roads. {@link #units} is summed
 * over the file's own rows, which came from {@code projection.participant_units} by way of the CDC
 * stream; {@link #unitsInIssue} is read live from {@code LedgerPort}. A row the snapshot dropped, a
 * position the projection never recorded, and a page the scan skipped all fail it, and none of them
 * is visible from inside the file.
 *
 * <p>{@link #value} is summed the same way, per position, so the manifest's per-fund total is
 * exactly the sum of the file's own {@code value} column and not a second multiplication over the
 * fund's total units — those differ by the rounding of every position.
 *
 * @param positionCount rows in the file for this fund
 * @param units {@code Σ units} over those rows, at 1e-8
 * @param value {@code Σ value} over those rows, MXN cents
 * @param unitsInIssue the fund's {@code UNITS_IN_ISSUE} ledger balance, read through {@code
 *     LedgerPort} — never summed from PostgreSQL (ADR-0008)
 */
public record SnapshotFundTotal(
    FundId fundId,
    long unitLedgerId,
    long positionCount,
    Units units,
    Money value,
    Units unitsInIssue) {

  public SnapshotFundTotal {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(unitsInIssue, "unitsInIssue must not be null");
    if (positionCount < 0) {
      throw new IllegalArgumentException("positionCount must not be negative");
    }
  }

  /** Whether the file's own unit total agrees with the ledger. Exact — there is no tolerance. */
  public boolean ties() {
    return units.equals(unitsInIssue);
  }

  /**
   * How far the file is from the ledger, signed, at 1e-8.
   *
   * <p>Negative means the file holds fewer units than the ledger issued — a position missing from
   * the snapshot, which is the failure this check exists to catch. Positive means the opposite, and
   * is just as much a finding. Deliberately unclamped (AGENTS.md §4).
   */
  public long driftScaled() {
    return Math.subtractExact(units.scaled(), unitsInIssue.scaled());
  }
}
