package dev.fondaria.eodjob.custodian;

import java.util.List;
import java.util.Objects;

/**
 * What one custodian-reconciliation pass found.
 *
 * @param breaksTouched every non-resolved {@link CustodianBreak} this pass opened or re-confirmed —
 *     the whole set live after this pass, not only the ones newly created, mirroring {@code
 *     ReconciliationResult#breaks()} counting every finding of the pass rather than only new ones
 * @param escalatedCount how many breaks this pass aged past their category's threshold
 */
public record CustodianReconciliationResult(
    CustodianReconciliationRun run,
    List<CustodianAccountTie> ties,
    List<CustodianBreak> breaksTouched,
    int escalatedCount) {

  public CustodianReconciliationResult {
    Objects.requireNonNull(run, "run must not be null");
    ties = List.copyOf(ties);
    breaksTouched = List.copyOf(breaksTouched);
  }

  public boolean passed() {
    return run.opensTheGate();
  }
}
