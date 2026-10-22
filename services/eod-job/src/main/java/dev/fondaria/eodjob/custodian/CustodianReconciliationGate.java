package dev.fondaria.eodjob.custodian;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The gate Task 7.5's sign-off calls before it reads custodian figures — the counterpart of {@code
 * ReconciliationGate} for Task 7.3. Fails closed the same three ways: no run for the date, a run
 * still {@code RUNNING}, or a run that {@code FAILED} (a real break open, or a statement missing).
 */
@Component
public class CustodianReconciliationGate {

  private final CustodianReconciliationRepository runs;

  public CustodianReconciliationGate(CustodianReconciliationRepository runs) {
    this.runs = Objects.requireNonNull(runs, "runs must not be null");
  }

  public boolean isOpen(LocalDate businessDate) {
    return runs.latestFor(businessDate).map(CustodianReconciliationRun::opensTheGate).orElse(false);
  }

  /**
   * Returns the latest custodian pass for this date if it passed, and throws otherwise. Returns the
   * run rather than {@code void} for the reason {@code ReconciliationGate#requirePassed} does — so
   * Task 7.5a's sign-off records the exact run without a second, racier read.
   */
  public CustodianReconciliationRun requirePassed(LocalDate businessDate) {
    CustodianReconciliationRun run =
        runs.latestFor(businessDate)
            .orElseThrow(
                () ->
                    new CustodianCloseBlockedException(
                        businessDate, "custodian reconciliation has not run for this date"));
    return switch (run.status()) {
      case PASSED -> run;
      case RUNNING ->
          throw new CustodianCloseBlockedException(
              businessDate, "custodian reconciliation " + run.runId() + " has not finished");
      case FAILED ->
          throw new CustodianCloseBlockedException(
              businessDate,
              "custodian reconciliation "
                  + run.runId()
                  + " found "
                  + run.realBreakCount()
                  + " real break(s) and "
                  + run.missingStatementCount()
                  + " missing statement(s)");
    };
  }
}
