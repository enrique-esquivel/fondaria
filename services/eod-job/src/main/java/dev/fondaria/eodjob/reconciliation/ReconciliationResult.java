package dev.fondaria.eodjob.reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * What one internal reconciliation pass found: the figures, and the disagreements among them.
 *
 * <p>The figures are returned alongside the breaks rather than only the verdict, because "it
 * failed" is not an operable statement — the first question is always which fund, by how much, and
 * against which of the three derivations.
 *
 * <p><b>The ties are over the whole platform, so a caller asserting on a shared database must
 * narrow to what it created</b> (AGENTS.md §4: never assert a total over a whole ledger, table or
 * topic). That is why {@link #unitTies()} is a list of per-fund ties rather than a single
 * platform-wide verdict.
 */
public record ReconciliationResult(
    ReconciliationRun run,
    List<FundUnitTie> unitTies,
    List<CashAccountTie> cashTies,
    List<ReconciliationBreak> breaks) {

  public ReconciliationResult {
    Objects.requireNonNull(run, "run must not be null");
    unitTies = List.copyOf(unitTies);
    cashTies = List.copyOf(cashTies);
    breaks = List.copyOf(breaks);
  }

  /** Whether this pass lets the rest of the close proceed. */
  public boolean passed() {
    return run.opensTheGate();
  }

  /** The tie for one fund, by its unit ledger id. */
  public java.util.Optional<FundUnitTie> tieForLedger(long unitLedgerId) {
    return unitTies.stream().filter(tie -> tie.unitLedgerId() == unitLedgerId).findFirst();
  }

  /** Every break raised for one fund's units. */
  public List<ReconciliationBreak> breaksForLedger(long unitLedgerId) {
    return breaks.stream()
        .filter(b -> b.unitLedgerId().filter(id -> id == unitLedgerId).isPresent())
        .toList();
  }
}
