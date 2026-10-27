package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.eodjob.schedule.CloseWindow;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled internal-reconciliation pass.
 *
 * <p><b>No transaction around the pass, unlike allotment-job's sweep.</b> That one holds a row lock
 * for the length of one batch's ledger call because it is changing a batch's status; this one
 * changes nothing another instance could conflict over. Two instances reconciling the same date
 * write two runs, and the gate reads whichever <i>settled</i> last — see {@link
 * ReconciliationRepository#latestFor}, which is where the ordering that makes concurrent passes
 * safe actually lives. Ordering by start time instead would let a quick passing run started second
 * permanently outrank a slower run started first that found real drift.
 *
 * <p><b>The exception is swallowed and logged, and that is deliberate.</b> A pass that throws has
 * already been recorded {@code FAILED} by {@link ReconciliationService}, so the gate is already
 * blocking and the gauge is already raised; letting it escape would only stop the scheduler from
 * ever trying again. The close must be able to recover by re-running once the cause is fixed,
 * without a redeploy.
 *
 * <p>A sweep outside the {@link CloseWindow} does nothing at all — no run row, no metric. Unset,
 * the window is always open.
 */
@Component
public class ReconciliationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationRunner.class);

  private final ReconciliationService reconciliation;
  private final ReconciliationMetrics metrics;
  private final CloseWindow window;

  public ReconciliationRunner(
      ReconciliationService reconciliation, ReconciliationMetrics metrics, CloseWindow window) {
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.window = Objects.requireNonNull(window, "window must not be null");
  }

  @Scheduled(fixedDelayString = "${fondaria.reconciliation.poll-interval}")
  public void sweep() {
    if (!window.admitsControls()) {
      return;
    }
    try {
      ReconciliationResult result = reconciliation.reconcile();
      metrics.record(result);
      if (!result.passed()) {
        LOGGER.error(
            "the daily close is blocked: runId={} businessDate={} breaks={}",
            result.run().runId(),
            result.run().businessDate(),
            result.run().breakCount());
      }
    } catch (RuntimeException e) {
      metrics.recordFailure();
      LOGGER.error("internal reconciliation pass did not complete", e);
    }
  }
}
