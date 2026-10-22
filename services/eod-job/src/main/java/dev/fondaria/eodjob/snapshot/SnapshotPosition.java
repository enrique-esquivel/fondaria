package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * One line of the dense snapshot: what a participant held in one fund at the close of one business
 * date, and what it was worth (phase-07 Task 7.4).
 *
 * <h2>Units are the pair, not one account</h2>
 *
 * <p>A holding is two ledger accounts — vested and unvested — and a {@code VESTING} transfer moves
 * units between them without changing the total. {@link #units} is their sum, which is the quantity
 * that multiplies by the NAV and the quantity that ties to {@code units_in_issue}. The split is
 * deliberately not carried: nothing in this task's acceptance criteria reads it, and a field a
 * snapshot writes but nothing verifies is a field that can be wrong for a year.
 *
 * <h2>Value is a multiplication, cost basis is a sum of them</h2>
 *
 * <p>{@code value = Money.value(units, nav)} — the one place units × NAV becomes pesos (AGENTS.md
 * §2), never re-derived here. {@code costBasis} arrives already summed by {@link CostBasisSource}
 * over the holding's allotment lots, each lot valued at the NAV that priced it.
 *
 * <p>{@code unrealisedGain = value − costBasis}, and it is <b>signed</b>. On a falling NAV it is
 * negative, and nothing anywhere clamps it (AGENTS.md §4). A suite that only ever exercised a
 * rising series would pass on an implementation that did.
 *
 * @param organizationId the employer whose participant this is
 * @param participantInternalRef the employer's own reference for the participant. Never rendered
 *     into a log line (AGENTS.md §7) — it is written to the snapshot file, which is the artefact
 *     statements are built from, and read nowhere else.
 * @param fundId the fund held
 * @param unitLedgerId that fund's unit ledger, carried so the file ties per fund without a lookup
 * @param units vested plus unvested, at 1e-8
 * @param nav the price this position is valued at
 * @param navVersionId which {@code reference.fund_nav} row that price came from. Without this the
 *     snapshot could not be re-derived after a NAV correction (AGENTS.md §4).
 * @param value {@code units × nav}, MXN cents
 * @param costBasis what the units held cost, MXN cents
 * @param unrealisedGain {@code value − costBasis}, signed
 */
public record SnapshotPosition(
    OrganizationId organizationId,
    String participantInternalRef,
    FundId fundId,
    long unitLedgerId,
    Units units,
    Nav nav,
    long navVersionId,
    Money value,
    Money costBasis,
    Money unrealisedGain) {

  public SnapshotPosition {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(costBasis, "costBasis must not be null");
    Objects.requireNonNull(unrealisedGain, "unrealisedGain must not be null");
  }

  /**
   * Builds a position, computing the two derived amounts rather than accepting them.
   *
   * <p>Taking {@code value} and {@code unrealisedGain} as constructor arguments would let a caller
   * supply a value that does not equal {@code units × nav}, which is the single arithmetic this
   * whole file exists to record. {@code Money#minus} is currency-checked and overflow-checked, so a
   * cost basis in another currency fails here rather than producing a nonsense gain.
   */
  public static SnapshotPosition of(
      OrganizationId organizationId,
      String participantInternalRef,
      FundId fundId,
      long unitLedgerId,
      Units units,
      Nav nav,
      long navVersionId,
      Money costBasis) {
    Money value = Money.value(units, nav);
    return new SnapshotPosition(
        organizationId,
        participantInternalRef,
        fundId,
        unitLedgerId,
        units,
        nav,
        navVersionId,
        value,
        costBasis,
        value.minus(costBasis));
  }
}
