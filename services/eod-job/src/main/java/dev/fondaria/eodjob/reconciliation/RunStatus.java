package dev.fondaria.eodjob.reconciliation;

/**
 * Where a reconciliation pass stands. Persisted as text and constrained in {@code
 * workflow.reconciliation_run}.
 *
 * <p><b>Only {@link #PASSED} opens the gate.</b> A pass that is still {@link #RUNNING} because its
 * process died is not a pass, and neither is one that {@link #FAILED} because the ledger was
 * unreachable. Everything downstream fails closed, which is the only safe default when the question
 * is whether participants' money is where the platform says it is.
 */
public enum RunStatus {
  RUNNING,
  PASSED,
  FAILED;

  public static RunStatus parse(String value) {
    return RunStatus.valueOf(value);
  }
}
