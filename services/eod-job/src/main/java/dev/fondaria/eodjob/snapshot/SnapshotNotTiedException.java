package dev.fondaria.eodjob.snapshot;

import java.time.LocalDate;
import java.util.List;

/**
 * A fund's unit total in the file did not equal its {@code units_in_issue} in the ledger.
 *
 * <p>This is the completeness check phase-07 Task 7.4 rests the whole artefact on, and it is the
 * one failure here that means <b>the file is wrong about participants' money</b> rather than merely
 * disagreeing with an earlier pass. A missing row, a page the scan skipped, a position the
 * projection never recorded: none of them is visible from inside the file, and all of them fail
 * this.
 *
 * <p>The file is not written. A snapshot that does not tie is not a snapshot, and storing it would
 * put an artefact in the archive that statements could later be rendered from.
 */
public class SnapshotNotTiedException extends SnapshotFailedException {

  private static final long serialVersionUID = 1L;

  private final transient List<SnapshotFundTotal> untiedFunds;

  public SnapshotNotTiedException(LocalDate businessDate, List<SnapshotFundTotal> untiedFunds) {
    super(businessDate, describe(untiedFunds));
    this.untiedFunds = List.copyOf(untiedFunds);
  }

  /** The funds that did not tie, each carrying both totals and the signed drift between them. */
  public List<SnapshotFundTotal> untiedFunds() {
    return untiedFunds;
  }

  /**
   * Names the funds and the drift, and nothing about a participant.
   *
   * <p>A fund id is not personal data and belongs in the reason an operator reads; which
   * participants are behind the drift is in the snapshot's own inputs, behind authentication
   * (docs/ENGINEERING-RULES.md §7).
   */
  private static String describe(List<SnapshotFundTotal> untiedFunds) {
    StringBuilder reason =
        new StringBuilder(
            untiedFunds.size()
                + " fund(s) did not tie to units in issue, so the file is incomplete:");
    for (SnapshotFundTotal total : untiedFunds) {
      reason
          .append(" fund ")
          .append(total.fundId().value())
          .append(" holds ")
          .append(total.units().scaled())
          .append(" in the file against ")
          .append(total.unitsInIssue().scaled())
          .append(" in issue (drift ")
          .append(total.driftScaled())
          .append(" at 1e-8);");
    }
    return reason.toString();
  }
}
