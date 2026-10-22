package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import java.util.Objects;

/**
 * One fund's three independent unit totals, as this pass found them.
 *
 * <p>Returned whether or not it ties, so a caller — a test, an operator report, Task 7.5's gate —
 * can see the figures rather than only the verdict. {@link #ties()} is the verdict.
 *
 * @param ledgerParticipantUnits Σ of every participant holding account, vested and unvested
 * @param ledgerUnitsInIssue the fund's {@code UNITS_IN_ISSUE} contra account
 * @param projectionUnits Σ of the latest {@code projection.participant_units} row per participant
 * @param holdingAccountsFound how many of this fund's holding accounts the ledger actually returned
 *     — recorded because "every participant holds nothing" and "no account was found" produce the
 *     same zero, and only one of them is a configuration error
 */
public record FundUnitTie(
    FundId fundId,
    long unitLedgerId,
    Units ledgerParticipantUnits,
    Units ledgerUnitsInIssue,
    Units projectionUnits,
    long holdingAccountsFound) {

  public FundUnitTie {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(ledgerParticipantUnits, "ledgerParticipantUnits must not be null");
    Objects.requireNonNull(ledgerUnitsInIssue, "ledgerUnitsInIssue must not be null");
    Objects.requireNonNull(projectionUnits, "projectionUnits must not be null");
  }

  /** All three agree exactly. Not "to within a tick" — exactly (phase-07 Task 7.2). */
  public boolean ties() {
    return ledgerParticipantUnits.equals(ledgerUnitsInIssue)
        && ledgerParticipantUnits.equals(projectionUnits);
  }
}
