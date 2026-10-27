package dev.fondaria.eodjob.custodian;

import dev.fondaria.eodjob.schedule.CloseWindow;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled custodian-reconciliation pass. Mirrors {@code ReconciliationRunner}: no transaction
 * around the pass (nothing another instance could conflict over — two concurrent passes write two
 * runs, and {@link CustodianReconciliationRepository#latestFor} reads whichever settled last), and
 * the exception is swallowed and logged rather than allowed to stop the scheduler from trying again
 * on the next tick. Outside the {@link CloseWindow} a sweep does nothing.
 */
@Component
public class CustodianReconciliationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(CustodianReconciliationRunner.class);

  private final CustodianReconciliationService reconciliation;
  private final CustodianReconciliationMetrics metrics;
  private final CloseWindow window;

  public CustodianReconciliationRunner(
      CustodianReconciliationService reconciliation,
      CustodianReconciliationMetrics metrics,
      CloseWindow window) {
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.window = Objects.requireNonNull(window, "window must not be null");
  }

  @Scheduled(fixedDelayString = "${fondaria.custodian-reconciliation.poll-interval}")
  public void sweep() {
    if (!window.admitsControls()) {
      return;
    }
    try {
      CustodianReconciliationResult result = reconciliation.reconcile();
      metrics.record(result);
      if (!result.passed()) {
        LOGGER.error(
            "custodian reconciliation is blocked: runId={} businessDate={} missingStatements={}"
                + " realBreaks={}",
            result.run().runId(),
            result.run().businessDate(),
            result.run().missingStatementCount(),
            result.run().realBreakCount());
      }
    } catch (RuntimeException e) {
      metrics.recordFailure();
      LOGGER.error("custodian reconciliation pass did not complete", e);
    }
  }
}
