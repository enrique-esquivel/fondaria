package dev.fondaria.eodjob.snapshot;

import dev.fondaria.eodjob.custodian.CustodianCloseBlockedException;
import dev.fondaria.eodjob.reconciliation.CloseBlockedException;
import dev.fondaria.eodjob.schedule.CloseWindow;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled dense-snapshot pass.
 *
 * <h2>It takes a date once, and this is the part that is easy to get wrong</h2>
 *
 * <p>The sweep's job is <b>"ensure today has a snapshot"</b>, not "re-derive today's snapshot". A
 * book is still moving during the day: an employer's batch allotted at 09:30 legitimately changes
 * the file. A scheduler that re-derived hourly would take a perfectly good snapshot at 09:00 and
 * then spend the rest of the day halting on a mismatch against it — raising {@code
 * eod_snapshot_missing} on a date whose snapshot exists and is fine, and writing a {@code FAILED}
 * row every hour until cut-off. So the sweep asks {@link SnapshotService#isTaken} first and stops
 * there when the answer is yes.
 *
 * <p>A deliberate re-run — an operator re-verifying a date, or a test — calls {@code snapshotFor}
 * directly, which still re-derives and still halts on a mismatch. The re-run check is not weakened;
 * it is taken off a timer.
 *
 * <p><b>No transaction around the pass</b>, matching {@code ReconciliationRunner}: it changes no
 * row another instance could conflict over until it settles its own run, and two instances racing
 * the same date are caught by {@code snapshot_run_one_of_record_per_date} (V28) rather than by a
 * lock.
 *
 * <p><b>Every exception is swallowed and logged, and that is deliberate</b> — the same reasoning
 * {@code ReconciliationRunner} records. A pass that threw has already been recorded {@code FAILED}
 * (or was refused by a gate before it started), so the state is already visible and the gauge is
 * already raised; letting it escape would only stop the scheduler from ever trying again. The close
 * must be able to recover by re-running once the cause is fixed, without a redeploy.
 *
 * <p>A blocked gate is logged at {@code warn} rather than {@code error}: it means an
 * <i>upstream</i> control is holding the close, which is that control's finding to page on and not
 * a second alert for the same event.
 *
 * <p><b>Outside the {@link CloseWindow} a sweep does nothing</b> — not even the {@code isTaken}
 * read. With a window configured, that is what stops the sweep from taking a date's snapshot of
 * record before the day's business has reached the book.
 *
 * <p><b>Known limitation, recorded rather than built</b> (`ADR-0020`): the sweep only ever
 * considers <i>today's</i> business date. A date that never got a snapshot — an object-store outage
 * outlasting the cut-off — is never retried, and once a later date succeeds {@code
 * eod_snapshot_missing} reads zero while the hole remains. Catching up past dates, and alerting on
 * a gap in the manifest chain, belong with Task 7.6's runbooks and Phase 11's alert rules.
 */
@Component
public class SnapshotRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotRunner.class);

  private final SnapshotService snapshots;
  private final SnapshotMetrics metrics;
  private final CloseWindow window;

  public SnapshotRunner(SnapshotService snapshots, SnapshotMetrics metrics, CloseWindow window) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.window = Objects.requireNonNull(window, "window must not be null");
  }

  @Scheduled(fixedDelayString = "${fondaria.snapshot.poll-interval}")
  public void sweep() {
    if (!window.admitsControls()) {
      return;
    }
    try {
      LocalDate businessDate = snapshots.businessDateNow();
      if (snapshots.isTaken(businessDate)) {
        metrics.recordAlreadyTaken();
        return;
      }
      metrics.record(snapshots.snapshot());
    } catch (CloseBlockedException | CustodianCloseBlockedException e) {
      metrics.recordFailure();
      LOGGER.warn("the daily snapshot did not run: {}", e.getMessage());
    } catch (SnapshotNotTiedException e) {
      metrics.recordFailure();
      metrics.recordUntied(e.untiedFunds().size());
      LOGGER.error("the daily snapshot does not tie to the ledger: {}", e.getMessage());
    } catch (RuntimeException e) {
      metrics.recordFailure();
      LOGGER.error("the daily snapshot pass did not complete", e);
    }
  }
}
