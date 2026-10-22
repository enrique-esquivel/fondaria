package dev.fondaria.eodjob.snapshot;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies the tamper-evident cryptographic hash chain across completed daily snapshot manifests
 * recorded in {@code workflow.snapshot_run} (V28).
 *
 * <p>Each business date's {@link SnapshotManifest} records {@code previousManifestSha256}, linking
 * each snapshot to the prior completed snapshot's {@code manifestSha256}. This verifier walks the
 * chain from genesis forward:
 *
 * <ul>
 *   <li>The genesis row (the first completed snapshot) must have no {@code previousManifestSha256}.
 *   <li>Every subsequent completed row must have a {@code previousManifestSha256} equal to the
 *       prior row's {@code manifestSha256}.
 *   <li>Every completed row must have a valid {@code manifestSha256}.
 * </ul>
 *
 * <p><b>Scope:</b> This verifier checks the recorded-hash linkage in PostgreSQL to fit the daily
 * close's tight time budget, without re-fetching external manifest objects from object storage.
 *
 * <p><b>Paging vs Blocking:</b> This verifier emits {@code eod_hash_chain_valid} (1 = valid, 0 =
 * broken). It does not write to the database or directly block the close; the {@code
 * EODHashChainInvalid} alert pages on-call immediately upon a 0 value.
 */
@Component
public class EodHashChainVerifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(EodHashChainVerifier.class);

  private final SnapshotRepository snapshots;
  private final AtomicLong valid = new AtomicLong(1L);

  public EodHashChainVerifier(SnapshotRepository snapshots, MeterRegistry registry) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    Objects.requireNonNull(registry, "registry must not be null");
    registry.gauge("eod_hash_chain_valid", valid, AtomicLong::doubleValue);
  }

  /**
   * Executes a verification pass over the completed snapshot runs, updating the {@code
   * eod_hash_chain_valid} gauge.
   *
   * @return {@code true} if the chain verifies end-to-end (or is empty), {@code false} if broken
   */
  public boolean verify() {
    try {
      List<SnapshotRun> completed = snapshots.completedRunsAscending();
      boolean chainValid = checkChain(completed);
      valid.set(chainValid ? 1L : 0L);
      return chainValid;
    } catch (RuntimeException e) {
      LOGGER.error("failed to verify EOD snapshot manifest hash chain", e);
      return false;
    }
  }

  private boolean checkChain(List<SnapshotRun> completed) {
    if (completed.isEmpty()) {
      return true;
    }
    SnapshotRun genesis = completed.get(0);
    if (genesis.previousManifestSha256().isPresent()) {
      LOGGER.error(
          "EOD hash chain invalid: genesis run {} on {} has previous_manifest_sha256={}",
          genesis.runId(),
          genesis.businessDate(),
          genesis.previousManifestSha256().get());
      return false;
    }
    if (genesis.manifestSha256().isEmpty()) {
      LOGGER.error(
          "EOD hash chain invalid: genesis run {} on {} has missing manifest_sha256",
          genesis.runId(),
          genesis.businessDate());
      return false;
    }

    for (int i = 1; i < completed.size(); i++) {
      SnapshotRun prior = completed.get(i - 1);
      SnapshotRun current = completed.get(i);

      if (current.manifestSha256().isEmpty()) {
        LOGGER.error(
            "EOD hash chain invalid: run {} on {} has missing manifest_sha256",
            current.runId(),
            current.businessDate());
        return false;
      }
      if (current.previousManifestSha256().isEmpty()) {
        LOGGER.error(
            "EOD hash chain invalid: non-genesis run {} on {} has missing previous_manifest_sha256",
            current.runId(),
            current.businessDate());
        return false;
      }
      String prevHash = current.previousManifestSha256().get();
      String priorHash = prior.manifestSha256().get();
      if (!prevHash.equals(priorHash)) {
        LOGGER.error(
            "EOD hash chain invalid: run {} on {} has previous_manifest_sha256={} but prior run {} on {} has manifest_sha256={}",
            current.runId(),
            current.businessDate(),
            prevHash,
            prior.runId(),
            prior.businessDate(),
            priorHash);
        return false;
      }
    }
    return true;
  }

  /** Current value of the eod_hash_chain_valid gauge (1 for valid, 0 for broken). */
  public long currentGaugeValue() {
    return valid.get();
  }
}
