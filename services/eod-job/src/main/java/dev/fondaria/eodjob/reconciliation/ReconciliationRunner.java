package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.eodjob.schedule.CloseWindow;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
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
 * <p>A sweep reconciles {@link CloseWindow}'s closing date — today, or the earliest date still
 * unsigned after its cut-off. Outside the window it does nothing at all — no run row, no metric.
 * Unset, the window is always open.
 *
 * <p><b>Inside the window it first asks {@link ProjectionCatchUp}</b> whether the projection has
 * reached the ledger's head, and skips the tick if not (ADR-0038): a pass against a projection
 * still draining can only fail, and each one re-reads the whole history. A skip writes no run row,
 * so the gate stays closed exactly as it would for a failed pass; it is counted and logged instead.
 */
@Component
public class ReconciliationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationRunner.class);

  private final ReconciliationService reconciliation;
  private final ReconciliationMetrics metrics;
  private final CloseWindow window;
  private final ProjectionCatchUp catchUp;

  public ReconciliationRunner(
      ReconciliationService reconciliation,
      ReconciliationMetrics metrics,
      CloseWindow window,
      ProjectionCatchUp catchUp) {
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.window = Objects.requireNonNull(window, "window must not be null");
    this.catchUp = Objects.requireNonNull(catchUp, "catchUp must not be null");
  }

  @Scheduled(fixedDelayString = "${fondaria.reconciliation.poll-interval}")
  public void sweep() {
    Optional<LocalDate> businessDate = window.dateForControls();
    if (businessDate.isEmpty()) {
      return;
    }
    try {
      ProjectionCatchUp.Decision decision = catchUp.check();
      if (!decision.runPass()) {
        metrics.recordSkipped();
        LOGGER.info(
            "internal reconciliation skipped, projection still catching up: businessDate={}"
                + " gapMillis={} consumersDraining={}",
            businessDate.get(),
            decision.gap().toMillis(),
            decision.busyConsumersBehind());
        return;
      }
      LOGGER.debug("internal reconciliation proceeding: {}", decision.reason());
      ReconciliationResult result = reconciliation.reconcile(businessDate.get());
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
