package dev.fondaria.eodjob.reconciliation;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The gate the rest of the daily close passes through: <b>drift blocks everything downstream</b>
 * (phase-07 Task 7.2).
 *
 * <p>Custodian reconciliation (7.3), the dense snapshot (7.4) and sign-off with statements (7.5)
 * each call {@link #requirePassed} before doing anything, and none of them may reach the ledger,
 * object storage or a statement renderer until it returns. Publishing a statement off a book that
 * does not tie is worse than publishing nothing: it puts a number in a participant's hands that the
 * platform cannot stand behind.
 *
 * <p><b>It fails closed, three ways.</b> No run for the date blocks. A run still {@code RUNNING} —
 * which is what a process killed mid-pass leaves — blocks. A run that {@code FAILED} blocks. Only
 * {@code PASSED} opens it, and only the latest run for the date is consulted, so a passing run this
 * morning does not authorise a close whose re-run this evening found drift.
 *
 * <p>"Latest" means <b>latest to settle</b>, not latest to start, and {@link
 * ReconciliationRepository#latestFor} carries the full reason: under two instances, ordering by
 * start time lets a quick passing run started second outrank a slower one started first that found
 * genuine drift, and the finding never reaches this gate at all.
 */
@Component
public class ReconciliationGate {

  private final ReconciliationRepository runs;

  public ReconciliationGate(ReconciliationRepository runs) {
    this.runs = Objects.requireNonNull(runs, "runs must not be null");
  }

  /** Whether the close for this date may proceed. */
  public boolean isOpen(LocalDate businessDate) {
    return runs.latestFor(businessDate).map(ReconciliationRun::opensTheGate).orElse(false);
  }

  /**
   * Returns the latest reconciliation for this date if it passed, and throws otherwise.
   *
   * <p>Returning the run — rather than {@code void} — is what lets a caller record <i>which</i> run
   * authorised a decision without a second {@link ReconciliationRepository#latestFor} call that a
   * concurrent settle could answer differently (Task 7.5a's sign-off names the run on {@code
   * workflow.close_signoff}). Callers that only need the pass/block verdict ignore the return.
   *
   * @throws CloseBlockedException naming why — no run, an unfinished one, or one that found drift
   */
  public ReconciliationRun requirePassed(LocalDate businessDate) {
    ReconciliationRun run =
        runs.latestFor(businessDate)
            .orElseThrow(
                () ->
                    new CloseBlockedException(
                        businessDate, "internal reconciliation has not run for this date"));
    return switch (run.status()) {
      case PASSED -> run;
      case RUNNING ->
          throw new CloseBlockedException(
              businessDate, "internal reconciliation " + run.runId() + " has not finished");
      case FAILED ->
          throw new CloseBlockedException(
              businessDate,
              "internal reconciliation "
                  + run.runId()
                  + " found "
                  + run.breakCount()
                  + " break(s)");
    };
  }
}
