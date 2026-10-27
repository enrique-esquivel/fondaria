package dev.fondaria.eodjob.signoff;

import dev.fondaria.eodjob.custodian.CustodianCloseBlockedException;
import dev.fondaria.eodjob.reconciliation.CloseBlockedException;
import dev.fondaria.eodjob.schedule.CloseWindow;
import dev.fondaria.eodjob.snapshot.EodHashChainVerifier;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled sign-off pass — the last step of the daily close.
 *
 * <p>Its job each tick is <b>"re-announce any stranded sign-off, then ensure the current business
 * date is signed off"</b>. {@link SignoffService#signOff()} re-publishes {@code
 * close.signed-off.v1} for every row whose event is not yet confirmed before it touches today, so a
 * broker outage that outlasts the cut-off recovers on the next healthy sweep rather than leaving
 * yesterday's event unpublished for good.
 *
 * <p><b>A blocked gate is logged at {@code warn}, not {@code error}</b>, exactly as {@code
 * SnapshotRunner} logs one: it means an <i>upstream</i> control is holding the close — internal
 * reconciliation, custodian reconciliation or the snapshot — and that control's own gauge is
 * already raised and its own runbook is what pages.
 *
 * <p><b>Every other exception is swallowed and logged, deliberately</b> — the same reasoning {@code
 * ReconciliationRunner} and {@code SnapshotRunner} record. Letting it escape would only stop the
 * scheduler from trying again; the close must recover by re-running once the cause is fixed, with
 * no redeploy.
 *
 * <p><b>Before the {@link CloseWindow} opens</b> a sweep still verifies the hash chain and still
 * re-announces any stranded sign-off — neither depends on today's book — but does not attempt
 * today's sign-off, and records neither a pass nor a block.
 *
 * <p>No transaction around the pass and no lock: two instances signing off the same date both write
 * through {@code ON CONFLICT (business_date) DO NOTHING}, and the second is a no-op.
 */
@Component
public class SignoffRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignoffRunner.class);

  private final SignoffService signoff;
  private final SignoffMetrics metrics;
  private final EodHashChainVerifier hashChainVerifier;
  private final CloseWindow window;

  public SignoffRunner(
      SignoffService signoff,
      SignoffMetrics metrics,
      EodHashChainVerifier hashChainVerifier,
      CloseWindow window) {
    this.signoff = Objects.requireNonNull(signoff, "signoff must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.hashChainVerifier =
        Objects.requireNonNull(hashChainVerifier, "hashChainVerifier must not be null");
    this.window = Objects.requireNonNull(window, "window must not be null");
  }

  @Scheduled(fixedDelayString = "${fondaria.signoff.poll-interval}")
  public void sweep() {
    hashChainVerifier.verify();
    if (!window.admitsSignoff()) {
      reannounceOnly();
      refreshAnnouncePendingGauge();
      return;
    }
    try {
      SignoffResult result = signoff.signOff();
      metrics.record(result);
      if (!result.announced()) {
        LOGGER.warn(
            "close {} is signed off but close.signed-off.v1 is not confirmed; will retry",
            result.businessDate());
      }
    } catch (CloseBlockedException | CustodianCloseBlockedException | SnapshotNotTakenException e) {
      metrics.recordBlocked();
      LOGGER.warn("the daily close is not yet ready to sign off: {}", e.getMessage());
    } catch (RuntimeException e) {
      metrics.recordBlocked();
      LOGGER.error("the sign-off pass did not complete", e);
    }
    refreshAnnouncePendingGauge();
  }

  private void reannounceOnly() {
    try {
      signoff.reannouncePending();
    } catch (RuntimeException e) {
      LOGGER.warn("could not re-announce pending sign-offs: {}", e.getMessage());
    }
  }

  /**
   * Set from its own table read so it survives a business-date rollover — even after a blocked
   * sweep.
   */
  private void refreshAnnouncePendingGauge() {
    try {
      metrics.setAnnouncePending(signoff.pendingAnnouncementCount());
    } catch (RuntimeException e) {
      LOGGER.warn("could not read the pending-announcement count: {}", e.getMessage());
    }
  }
}
